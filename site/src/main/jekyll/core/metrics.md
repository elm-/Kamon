---
title: Kamon | Core | Documentation
layout: default
---

Metrics
=======

Some intro about metrics

Philosophy
----------

Back in the day, the most common approach to get metrics out of an Akka/Spray application for production monitoring was
doing manual instrumentation: select your favorite metrics collection library, wrap you messages with some useful
metadata, wrap your actor's receive function with some metrics measuring code and, finally, push that metrics data out
to somewhere you can keep it, graph it and analyse it whenever you want.

Each metrics collection library has it's own strengths and weaknesses, and each developer has to choose wisely according
to the requirements they have in hand, leading them in different paths as they progress with their applications. Each
path has different implications with regards to introduced overhead and latency, metrics data accuracy and memory
consumption. Kamon takes this responsibility out of the developer and tries to make the best choice to provide high
performance metrics collection instruments while keeping the inherent overhead as low as possible.

Kamon tries to select the best possible approach, so you don't have to.


Metrics Collection and Flushing
-------------------------------

All the metrics infrastructure in Kamon was designed around two concepts: collection and flushing. Metrics collection
happens in real time, as soon as the information is available for being recorded. Let's see a simple example: as soon as
a actor finishes processing a message, Kamon knows the elapsed time for processing that specific message and it is
recorded right away. If you have millions of messages passing through your system, then millions of measurements will be
taken.

Flushing happens recurrently after a fixed amount of time has passed, a tick. Upon each tick, Kamon will collect all
measurements recorded since the last tick, flush the collected data and reset all the instruments to zero. Let's explore
a little bit more on how this two concepts are modeled inside Kamon.

<img class="img-responsive" src="/assets/img/diagrams/metric-collection-concepts.png">

A metric group contains various individual metrics that are related to the same entity, for example, if the entity we
are talking about is an actor, the metrics related to processing time, mailbox size and time in mailbox for that
specific actor are grouped inside a single metric group, and each actor gets its own metric group. As you might disguise
from the diagram above, on the left we have the mutable side of the process that is constantly recoding measurements as
the events flow through your application and on the right we have the immutable side, containing snapshots representing
all the measurements taken during a specific period on time for a metric group.


Filtering Entities
------------------

By default Kamon will not include any entity for metrics collection and you will need to explicitly include all the
entities you are interested in, be it a actor, a trace, a dispatcher or any other entity monitored by Kamon. The
`kamon.metrics.filters` key on your application's configuration controls which entities must be included/excluded from
the metrics collection infrastructure. Includes and excludes are provided as lists of strings containing the
corresponding GLOB patterns for each group, and the logic behind is simple: include everything that matches at least one
`includes` pattern and does not match any of the `excludes` patterns. The following configuration file sample includes
the `user/job-manager` actor and all the worker actors, but leaves out all system actors and the `user/worker-helper`
actor.

```
kamon {
  metrics {
    filters = [
      {
        actor {
          includes = [ "user/job-manager", "user/worker-*" ]
          excludes = [ "system/*", "user/worker-helper" ]
        }
      },
      {
        trace {
          includes = [ "*" ]
          excludes = []
        }
      }
    ]
  }
}
```

Instruments
-----------

The most common thing that Kamon will help you measure is latency: how much time did the application spend doing
something. Be it the time taken for processing a message within a actor, or how long did it take to serve a HTTP
request, Kamon needs to provide you with accurate data while keeping the overhead of metrics collection as low as
possible. Now a question raises, what is the best way to show information about latency?, is it to show averages? (oh
no, hell no!), is it to show percentiles / what percentiles should we show?, it depends a lot on what do you need this
numbers for, so Kamon decided to go with a simple approach to keep everyone happy: we record all the data; there is no
statistical representation of the latency distribution, no running averages, just your plain data, __all your data__,
then you decide what kind of analysis you want to do with it.

As you might guess, keeping all the latency measurements is not an easy task and some tradeoffs have to be made,
specially when monitoring systems that can process millions of events per second in order to keep resources consumption
to the minimum. After experimenting with some of the most used libraries for metrics collection on the JVM, Kamon
settled with the not so widely known [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) implementation created
by [Gil Tene](https://twitter.com/giltene). Let's explore what does the HdrHistogram brings to the table:

* Fixed



Subscription protocol
---------------------

Explain how to subscribe for metrics data and provide a simple example.
