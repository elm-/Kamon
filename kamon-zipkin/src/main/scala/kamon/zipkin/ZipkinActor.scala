package kamon.zipkin

import java.net.InetAddress
import java.nio.ByteBuffer
import javax.xml.bind.DatatypeConverter

import akka.actor.{ActorLogging, Actor}
import scala.concurrent.duration._
import kamon.{NanoInterval, NanoTimestamp}
import kamon.trace.TraceInfo
import kamon.zipkin.util.TReusableTransport
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.{TSocket, TFramedTransport}

import scala.util.Random

class ZipkinActor(config: ZipkinConfig) extends Actor with ActorLogging {
  import ZipkinActor._

  private implicit val ec = context.dispatcher

  private val protocolFactory = new TBinaryProtocol.Factory()
  private val thriftBuffer = new TReusableTransport()

  private val transport = new TFramedTransport(new TSocket(config.collector.host, config.collector.port))
  private val client = new thrift.Scribe.Client(new TBinaryProtocol(transport))

  private var retryCounter = 0
  private var scheduledSpans: List[thrift.Span] = Nil


  override def preStart() = {
    scheduleFlush()
  }

  def receive = {
    case trace: TraceInfo ⇒
      scheduledSpans = traceInfoToSpans(trace) ::: scheduledSpans
      if (scheduledSpans.size > config.collector.maxScheduledSpans)
        scheduledSpans = scheduledSpans.take(config.collector.maxScheduledSpans)
    case Flush =>
      flush()
  }


  override def postStop() = {
    flush()
    transport.close()
  }

  private def flush() {
    if (scheduledSpans.isEmpty) return

    import scala.collection.JavaConversions._

    if (! transport.isOpen()) {
      log.debug("Connected to Zipkin collector")
      transport.open()
    }

    try {
      log.debug("Flushing ${scheduleSpans.size} spans to Zipkin collector")
      client.Log(scheduledSpans.map(logEntryFromSpan))
      log.debug("Successfully flushed ${scheduleSpans.size} spans to Zipkin collector")
      scheduledSpans = Nil
      scheduleFlush()
    } catch {
      case e: Exception =>
        log.error(e, s"Could not send trace data to Zipkin collector: ${e.getMessage()}")
        scheduleFlush(false)
    }
  }

  private def scheduleFlush(success: Boolean = true): Unit = {
    if (! success) {
      retryCounter += 1
    } else {
      retryCounter = 0
    }
    val interval = retryCounter match {
      case 0 | 1 | 2 => config.collector.flushInterval.millis
      case x => Math.min(config.collector.flushInterval * x, config.collector.maxFlushInterval).millis
    }
    context.system.scheduler.scheduleOnce(interval, self, Flush)
  }

  private def timestampToMicros(nano: NanoTimestamp) = nano.nanos / 1000
  private def durationToMicros(nano: NanoInterval) = nano.nanos / 1000


  private def simpleSpan(traceId: Long, spanId: Long, name: String, start: Long, duration: Long,
                         annotations: Map[String, String], parentSpanId: Long = 0,
                         endpoint: thrift.Endpoint = createApplicationEndpoint()) = {
    val sr = new thrift.Annotation()
    sr.set_timestamp(start)
    sr.set_value(thrift.zipkinConstants.SERVER_RECV)
    sr.set_host(endpoint)

    val ss = new thrift.Annotation()
    ss.set_timestamp(start + duration)
    ss.set_value(thrift.zipkinConstants.SERVER_SEND)
    ss.set_host(sr.get_host())

    val span = new thrift.Span()
    span.set_trace_id(traceId)
    span.set_id(spanId)
    span.set_parent_id(parentSpanId)
    span.set_name(name)
    span.add_to_annotations(sr)
    span.add_to_annotations(ss)
    annotations.foreach { case (k, v) => span.add_to_binary_annotations(stringAnnotation(k, v)) }
    span
  }

  private def longHash(string: String): Long = {
    var h = 1125899906842597L
    val len = string.length
    for (i <- 0 until len) {
      h = 31 * h + string.charAt(i)
    }
    h ^ sessionLong
  }

  private def traceInfoToSpans(trace: TraceInfo) = {
    val rootToken = trace.metadata.getOrElse(ZipkinTracing.rootToken, trace.token)
    val parentToken = trace.metadata.get(ZipkinTracing.parentToken)
    val token = trace.token

    val globalAnnotations = Map(
      "token" -> token,
      "rootToken" -> rootToken
    ) ++ (parentToken match {
      case Some(parentToken) => Map("parentToken" -> parentToken)
      case None => Map.empty
    })

    val traceId = longHash(rootToken)
    val rootSpanId = longHash(token)
    val parentSpanId = parentToken.map(longHash).getOrElse(0L)

    val endpoint = (trace.metadata.isDefinedAt(ZipkinTracing.clientServiceName) && trace.metadata.isDefinedAt(ZipkinTracing.clientServiceHost)) match {
      case true => createEndpoint(trace.metadata(ZipkinTracing.clientServiceName), trace.metadata(ZipkinTracing.clientServiceHost), trace.metadata.getOrElse(ZipkinTracing.clientServicePort, "0").toInt)
      case false => createApplicationEndpoint()
    }

    val root = simpleSpan(traceId, rootSpanId, trace.name, timestampToMicros(trace.timestamp), durationToMicros(trace.elapsedTime), globalAnnotations ++ trace.metadata, parentSpanId, endpoint)
    val children = trace.segments.map { segment =>
      val segmentAnnotations = Map(
        "category" -> segment.category,
        "library" -> segment.library
      )
      simpleSpan(traceId, Random.nextLong(), segment.name, timestampToMicros(segment.timestamp), durationToMicros(segment.elapsedTime), globalAnnotations ++ segmentAnnotations ++ trace.metadata, 0, endpoint)
    }
    root :: children
  }

  private def stringAnnotation(key: String, value: String) = {
    val a = new thrift.BinaryAnnotation()
    a.set_annotation_type(thrift.AnnotationType.STRING)
    a.set_key(key)
    a.set_value(ByteBuffer.wrap(value.getBytes))
    a.set_host(createApplicationEndpoint())
    a
  }


  private def createApplicationEndpoint() =
    createEndpoint(config.service.name, config.service.host, config.service.port)

  private def createEndpoint(service: String, host: String, port: Int): thrift.Endpoint =
    createEndpoint(service, InetAddress.getByName(host), port)

  private def createEndpoint(service: String, host: InetAddress, port: Int): thrift.Endpoint =
    createEndpoint(service, ByteBuffer.wrap(host.getAddress).getInt, port.toShort)

  private def createEndpoint(service: String, host: Int, port: Short): thrift.Endpoint = {
    val e = new thrift.Endpoint()
    e.set_service_name(service)
    e.set_ipv4(host)
    e.set_port(port)
    e
  }

  private def logEntryFromSpan(span: thrift.Span): thrift.LogEntry = {
    span.write(protocolFactory.getProtocol(thriftBuffer))
    val thriftBytes = thriftBuffer.getArray.take(thriftBuffer.length)
    thriftBuffer.reset()
    val encodedSpan = DatatypeConverter.printBase64Binary(thriftBytes) + '\n'
    new thrift.LogEntry("zipkin", encodedSpan)
  }
}


object ZipkinActor {
  /**
   * to create unique trace and span IDs based on tokens, as tokens are only unique per application run,
   * a unique salt is used to generate global random IDs that are required by Zipkin.
   */
  private val sessionLong = Random.nextLong()

  sealed trait ZipkinActorProtocol
  case object Flush extends ZipkinActorProtocol
}