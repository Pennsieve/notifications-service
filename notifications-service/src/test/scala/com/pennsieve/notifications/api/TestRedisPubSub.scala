package com.pennsieve.notifications.api

import akka.stream.alpakka.redis.scaladsl._
import akka.stream.alpakka.redis._
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import java.time.Instant

import com.redis._
import com.pennsieve.notifications._
import com.pennsieve.notifications.{
  DatasetUpdateNotification,
  NotificationMessage,
  Pong
}
import org.scalatest.{ Matchers, WordSpec }
import io.lettuce.core.RedisClient

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import com.pennsieve.models.CognitoId.UserPoolId
import com.pennsieve.core.utilities.UserAuthContext
import com.pennsieve.aws.cognito.CognitoPayload

class TestRedisPubSub
    extends WordSpec
    with NotificationsDatabaseBaseSpec
    with Matchers {

  var redisClient: RedisClient = _

  override def afterStart(): Unit = {
    super.afterStart()
    redisClient = RedisClient.create(
      s"redis://${redisContainer.containerIpAddress}:${redisContainer.mappedPort}/0"
    )
  }

  override def afterAll(): Unit = {
    redisClient.getResources().shutdown()
    redisClient.shutdown()
    super.afterAll()
  }

  "Pong timeout Flow" should {

    "consume pong messages without timing out" in {
      val sessionId = "12345"
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val (sourceProbe, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(PongMonitor(1.second, authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      sourceProbe.sendNext(
        Pong(users = List(0), message = "PONG", sessionId = sessionId)
      )
      sourceProbe.sendComplete()
      sinkProbe.expectSubscriptionAndComplete()
    }

    "timeout and error when it does not see a pong" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val (_, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(PongMonitor(1.second, authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      Thread.sleep(2000)

      sinkProbe.expectSubscriptionAndError() shouldBe an[TimeoutException]
    }

  }

  "Session validator flow" should {

    "stream normally while session is valid" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val messages = List(1 to 5) map (
        i => Pong(users = List(0), message = s"PONG $i", sessionId = "54321")
      )

      val sinkProbe = Source(messages)
        .throttle(1, 1.second)
        .via(SessionMonitor(authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.right)
        .run()

      sinkProbe.request(n = 5)
      messages.map(sinkProbe.expectNext(_))
      sinkProbe.expectComplete()
    }

    "cancel stream with error when the session is no longer valid" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(1))
        )
      )

      val (sourceProbe, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(SessionMonitor(authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      val msg = Pong(users = List(0), message = "PONG", sessionId = "54321")

      sourceProbe.sendNext(msg)
      sinkProbe.request(n = 1)
      sinkProbe.expectNext(msg)

      Thread.sleep(2000)

      sourceProbe.sendNext(msg)
      sinkProbe.request(n = 1)
      sinkProbe.expectError() shouldBe SessionExpired
    }
  }

  "pub sub" should {

    "broadcast notifications through Redis" in {
      val notificationStream1 = new NotificationStream(config)
      val notificationStream2 = new NotificationStream(config)
      val notificationStream3 = new NotificationStream(config)

      val notification = DatasetUpdateNotification(
        users = List(1),
        datasetId = 2,
        datasetName = "Test dataset",
        message = "Status changed"
      )

      val receivedOnStream1 = notificationStream1.notificationSource
        .toMat(Sink.head)(Keep.right)
        .run()

      val receivedOnStream2 = notificationStream2.notificationSource
        .toMat(Sink.head)(Keep.right)
        .run()

      Source
        .single(notification)
        .runWith(notificationStream3.notificationSink)

      receivedOnStream1.await shouldBe notification
      receivedOnStream2.await shouldBe notification
    }

    /**
      * The rest of these tests are from
      * https://github.com/akka/alpakka/pull/1350 and should be removed when the
      * PR is merged and the dependency pulled in.
      */
    "implement pub/sub for single topic and return notification of single consumer" in {
      val topic = "topic20"

      RedisSource
        .subscribe(immutable.Seq(topic), redisClient.connectPubSub())
        .runWith(Sink.ignore)

      Thread.sleep(1000)

      val result = Source
        .single("Bla")
        .map(f => RedisPubSub(topic, f))
        .via(
          RedisFlow.publish[String, String](
            1,
            redisClient.connectPubSub().async().getStatefulConnection
          )
        )
        .runWith(
          Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]]
        )
      Await.result(result, 5.seconds).result.get shouldEqual 1L
    }

    "implement pub/sub for single topic and return published element from consumer" in {
      val topic = "topic0"

      val recievedMessage = RedisSource
        .subscribe(immutable.Seq(topic), redisClient.connectPubSub())
        .runWith(Sink.head[RedisPubSub[String, String]])

      Thread.sleep(1000)

      Source
        .single("Bla")
        .map(f => RedisPubSub(topic, f))
        .via(
          RedisFlow.publish[String, String](
            1,
            redisClient.connectPubSub().async().getStatefulConnection
          )
        )
        .runWith(
          Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]]
        )

      Await.result(recievedMessage, 5.seconds) shouldEqual RedisPubSub(
        topic,
        "Bla"
      )

      redisClient.connectPubSub().sync().unsubscribe(topic)
    }

    "implement pub/sub for multiple values " in {

      val messages = Seq[RedisPubSub[String, String]](
        RedisPubSub("topic3", "value4"),
        RedisPubSub("topic2", "value2")
      )

      val receivedMessages =
        RedisSource
          .subscribe(
            immutable.Seq("topic3", "topic2"),
            redisClient.connectPubSub()
          )
          .grouped(2)
          .runWith(Sink.head)

      Thread.sleep(1000)

      Source
        .fromIterator(() => messages.iterator)
        .via(
          RedisFlow.publish[String, String](
            1,
            redisClient.connectPubSub().async().getStatefulConnection
          )
        )
        .runWith(
          Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]]
        )

      Await.result(receivedMessages, 5.seconds) shouldEqual messages
    }
  }
}
