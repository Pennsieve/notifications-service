package com.blackfynn.notifications.api

import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.NotUsed
import com.blackfynn.managers.SessionManager
import com.blackfynn.notifications._
import scala.concurrent.duration._

case object SessionExpired extends Throwable

/**
  * Pass-through flow that routinely checks the validity of a user session. If
  * the session has expired, the stream is completed with an error.
  *
  * This is a "side-car" flow that does not affect the original stream of
  * elements.
  */
object SessionMonitor {

  def apply(
    timeout: FiniteDuration,
    sessionId: String,
    sessionManager: SessionManager
  ): Flow[NotificationMessage, NotificationMessage, NotUsed] =
    Flow.fromGraph(GraphDSL.create() {
      implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val tick = builder.add(
          Source.tick(
            timeout,
            timeout,
            KeepAlive(List(), MessageType.KeepAliveT, "Tick")
          )
        )

        // Validate the session against the Redis session store
        val checkSession = builder.add(
          Flow[NotificationMessage]
            .map(
              t =>
                sessionManager.get(sessionId) match {
                  case Right(_) => t
                  case Left(_) => throw SessionExpired
                }
            )
            .filter(_ => false)
        )

        val merge =
          builder.add(Merge[NotificationMessage](2, eagerComplete = true))

        tick ~> checkSession ~> merge.in(0)

        FlowShape(merge.in(1), merge.out)
    })
}
