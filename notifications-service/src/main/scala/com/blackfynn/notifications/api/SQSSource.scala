// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.notifications.api

import com.blackfynn.aws.queue.SQS
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db.{ DatasetsMapper, OrganizationsMapper, PackagesMapper }
import com.blackfynn.dtos.PackageDTO
import com.blackfynn.dtos.Builders.insecure_basicPackageDTO
import com.blackfynn.models.{ Dataset, User }
import com.blackfynn.notifications.MessageType._
import com.blackfynn.notifications.{ NotificationMessage, _ }
import com.blackfynn.notifications.api.NotificationWebServer.DIContainer
import com.blackfynn.traits.PostgresProfile.api._
import akka.actor.ActorSystem
import akka.{ Done, NotUsed }
import akka.http.scaladsl.model.ws.Message
import akka.stream.alpakka.sqs.MessageAction
import akka.stream.alpakka.sqs.scaladsl.{ SqsAckSink, SqsSource }
import akka.stream.contrib.PartitionWith
import akka.stream._
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Merge,
  Partition,
  RestartSource,
  Sink,
  Source
}
import cats.data.EitherT
import cats.implicits._
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.blackfynn.domain.{ CoreError, Error, NotFound, ParseError, SqlError }
import com.blackfynn.service.utilities.Tier
import com.typesafe.config.Config
import io.circe.java8._
import io.circe.java8.time._
import io.circe.parser.decode
import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class SQSSource(
  config: Config,
  container: DIContainer
)(implicit
  system: ActorSystem,
  materializer: ActorMaterializer,
  executionContext: ExecutionContext
) {
  implicit val tier: Tier[SQSSource] = Tier[SQSSource]

  type MessageNotificationPair = (SQSMessage, NotificationMessage)
  type MessageExceptionPair = (SQSMessage, CoreError)

  val sqs: SQS = container.sqs
  val queue: String = config.as[String]("sqs.notifications_queue")
  val parallelism: Int = config.as[Int]("parallelism")

  val minBackoff = config.as[FiniteDuration]("notifications.retry.minBackoff")
  val maxBackoff = config.as[FiniteDuration]("notifications.retry.maxBackoff")
  val randomFactor = config.as[Double]("notifications.retry.randomFactor")
  val maxRestarts = config.as[Int]("notifications.retry.maxRestarts")

  val rateLimitElementCount: Int =
    config.as[Int]("notifications.rateLimit.elements")
  val rateLimitTimeSpan: FiniteDuration =
    config.as[Int]("notifications.rateLimit.seconds").seconds

  val errorToAction: MessageExceptionPair => MessageAction = {
    case (message, exception) =>
      container.log.tierNoContext.error(
        s"SQSSource failed to process SQS message: ${message.messageId}",
        exception
      )
      MessageAction.Ignore(message)
  }

  val success: MessageNotificationPair => MessageAction = {
    case (message, _) => MessageAction.Delete(message)
  }

  val sqsSource: Source[SQSMessage, NotUsed] =
    RestartSource.onFailuresWithBackoff(
      minBackoff = minBackoff,
      maxBackoff = maxBackoff,
      randomFactor = randomFactor,
      maxRestarts = maxRestarts
    )(() => {
      container.log.tierNoContext.info("Starting SQSSource")
      SqsSource(queue)(sqs.client)
    })

  type MaybePair =
    Either[(SQSMessage, CoreError), (SQSMessage, NotificationMessage)]

  def parse(message: SQSMessage): MaybePair = {
    val json = message.body

    decode[NotificationMessage](json)
      .map[MessageNotificationPair] { notification =>
        (message, notification)
      }
      .leftMap[MessageExceptionPair] { error =>
        (message, ParseError(error))
      }
  }

  def parser
    : PartitionWith[SQSMessage, MessageExceptionPair, MessageNotificationPair] =
    PartitionWith.apply(parse)

  val handleNotifications: MessageNotificationPair => Future[MaybePair] = {
    case (message: SQSMessage, notification: ETLNotification) =>
      getEntities(notification.organizationId, notification.packageId)(
        container
      ).map[NotificationMessage] {
          case (dataset, packageDTO) =>
            JobDoneNotification(
              notification.users,
              JobDone,
              dataset.name,
              packageDTO,
              s"Processing for package ${packageDTO.content.name} in dataset ${dataset.name} is complete."
            )
        }
        .fold(e => (message, e).asLeft, jobDone => (message, jobDone).asRight)

    case (message: SQSMessage, notification: ETLExportNotification) =>
      getEntities(notification.organizationId, notification.sourcePackageId)(
        container
      ).map[NotificationMessage] {
          case (dataset, packageDTO) =>
            JobDoneNotification(
              notification.users,
              JobDone,
              dataset.name,
              packageDTO,
              s"Export for package ${packageDTO.content.name} in dataset ${dataset.name} is complete."
            )
        }
        .fold(e => (message, e).asLeft, jobDone => (message, jobDone).asRight)

    case (message: SQSMessage, notification: DatasetImportNotification) =>
      Future.successful(
        Right(message, notification.asInstanceOf[NotificationMessage])
      )

    case (message: SQSMessage, notification: DatasetPublishNotification) =>
      Future.successful(
        Right(message, notification.asInstanceOf[NotificationMessage])
      )

    case (message: SQSMessage, notification: DiscoverPublishNotification) =>
      Future.successful(
        Right(message, notification.asInstanceOf[NotificationMessage])
      )

    case (message: SQSMessage, notification: UploadNotification) =>
      getEntities(notification.organizationId, notification.packageId)(
        container
      ).map[NotificationMessage] {
          case (dataset, packageDTO) =>
            JobDoneNotification(
              notification.users,
              JobDone,
              dataset.name,
              packageDTO,
              s"Uploading for package ${packageDTO.content.name} in dataset ${dataset.name} is complete."
            )
        }
        .fold(e => (message, e).asLeft, jobDone => (message, jobDone).asRight)

    // This case should not be reached, but prevents the stream from failing in
    // if a new notification message is created and this match statement is not
    // updated
    case (message: SQSMessage, notification) =>
      Future.successful(
        Left((message, Error(s"Unsupported notification: $notification")))
      )
  }

  val handleNotificationsFlow
    : Flow[MessageNotificationPair, MaybePair, NotUsed] =
    Flow[MessageNotificationPair]
      .throttle(rateLimitElementCount, rateLimitTimeSpan)
      .mapAsync[MaybePair](parallelism)(handleNotifications)

  def killswitch
    : Graph[FlowShape[MessageAction, MessageAction], UniqueKillSwitch] =
    KillSwitches.single[MessageAction]

  val ackSink: Sink[MessageAction, Future[Done]] =
    SqsAckSink(queue)(sqs.client)

  val wsMessageSource: Source[NotificationMessage, UniqueKillSwitch] =
    Source.fromGraph(GraphDSL.create(killswitch) {
      implicit builder: GraphDSL.Builder[UniqueKillSwitch] => kill =>
        import GraphDSL.Implicits._

        val bcast = builder.add(Broadcast[MessageNotificationPair](2))

        // Parse json
        val parse = builder.add(parser)
        val failedParse = parse.out0
        val successfulParse = parse.out1

        // Handle each notification type
        val handleNotifications = builder.add(handleNotificationsFlow)

        val merge =
          builder.add(Merge[MessageAction](4, eagerComplete = true))
        val ack = builder.add(ackSink)

        val partitioner: ((SQSMessage, NotificationMessage)) => Int = {
          case (_, notification) =>
            notification.messageType match {
              case JobDone ⇒ 0
              case DatasetUpdate => 0
              case _ ⇒ 1
            }
        }
        // Partition by notification type
        val partitionByMessageType =
          builder.add(Partition[MessageNotificationPair](2, partitioner))

        // Split errors and successes
        val handler = builder.add(
          PartitionWith[
            MaybePair,
            MessageExceptionPair,
            MessageNotificationPair
          ](identity)
        )
        val handlerFailed = handler.out0
        val handlerSucceeded = handler.out1

        val getNotification = builder.add(
          Flow[MessageNotificationPair]
            .map {
              case (_: SQSMessage, notification: NotificationMessage) =>
                notification
            }
        )

        val createError =
          Flow[MessageNotificationPair]
            .map {
              case (m: SQSMessage, notification: NotificationMessage) =>
                (
                  m,
                  Error(
                    s"Unsupported message type: ${notification.messageType}"
                  )
                )
            }
            .map(errorToAction)

        val toSuccessAction = Flow[MessageNotificationPair].map(success)
        def toErrorAction = Flow[MessageExceptionPair].map(errorToAction)

        // @formatter:off

        sqsSource ~> parse.in

        successfulParse ~> partitionByMessageType ~> handleNotifications ~> handler.in

                           partitionByMessageType ~>  createError  ~> merge ~> kill ~> ack

                                      failedParse ~> toErrorAction ~> merge

                                    handlerFailed ~> toErrorAction ~> merge

                      handlerSucceeded ~> bcast ~> toSuccessAction ~> merge

                                          bcast ~> getNotification

        // @formatter:on

        SourceShape(getNotification.out)
    })

  def getEntities(
    organizationId: Int,
    packageId: Int
  )(implicit
    insecureContainer: DIContainer
  ): EitherT[Future, CoreError, (Dataset, PackageDTO)] = {
    val query = for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      packagesMapper = new PackagesMapper(organization)
      pkg <- packagesMapper.getPackage(packageId)
      dataset <- new DatasetsMapper(organization)
        .getDataset(pkg.datasetId)
    } yield (organization, pkg, dataset)

    for {
      entities <- insecureContainer.db
        .run(query.transactionally)
        .toEitherT
        .leftMap[CoreError](
          _ => SqlError("Failed to retrieve notification entities")
        )
      (organization, pkg, dataset) = entities
      dto <- insecure_basicPackageDTO(pkg, dataset, organization)(
        executionContext,
        insecureContainer
      )
    } yield (dataset, dto)
  }

}
