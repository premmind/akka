# Sink.reduce

Apply a reduction function on the incoming elements and pass the result to the next invocation.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #reduce }

@@@

## Description

Apply a reduction function on the incoming elements and pass the result to the next invocation. The first invocation
receives the two first elements of the flow.

Materializes into a @scala[`Future`] @java[`CompletionStage`] that will be completed by the last result of the reduction function.

@@@div { .callout }

**cancels** never

**backpressures** when the previous reduction function invocation has not yet completed

@@@

