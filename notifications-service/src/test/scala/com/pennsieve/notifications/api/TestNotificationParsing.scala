package com.pennsieve.notifications.api

import com.pennsieve.notifications.{ AlertNotification, UploadNotification }
import com.pennsieve.notifications.MessageType.Alert
import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.RequestEntity
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.mdedetrich.akka.http.support.CirceHttpSupport._
import org.scalatest.{ FlatSpec, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class TestNotificationParsing extends FlatSpec with Matchers {

  implicit val system = ActorSystem("test-system")
  implicit val executionContext = system.dispatcher

  "a notification" should "get parsed" in {
    val notification = AlertNotification(List(1), Alert, "alert Message")

    val parsedFuture = Marshal(notification)
      .to[RequestEntity]
      .flatMap(Unmarshal(_).to[AlertNotification])

    val parsed = Await.result(parsedFuture, 10.seconds)

    assert(parsed == notification)
  }

  "a upload notification" should "get parsed" in {
    val notification =
      UploadNotification(List(1), true, 1, 1, 1, List("somefile"))

    val parsedFuture = Marshal(notification)
      .to[RequestEntity]
      .flatMap(Unmarshal(_).to[UploadNotification])

    Await.result(parsedFuture, 10.seconds) shouldBe notification
  }
}
