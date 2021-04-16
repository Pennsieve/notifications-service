package com.pennsieve.notifications.api

import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.NotUsed
import com.pennsieve.core.utilities.UserAuthContext
import com.pennsieve.notifications._
import java.time.Instant
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
    authContext: UserAuthContext
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

        // Validate the session
        val checkSession = builder.add(
          Flow[NotificationMessage]
            .map(
              t =>
                authContext.cognitoPayload match {
                  case Some(payload)
                      if payload.expiresAt.isAfter(Instant.now()) =>
                    t
                  case _ => throw SessionExpired
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
