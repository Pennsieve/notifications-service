package com.pennsieve.notifications.api

import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.core.utilities._
import com.pennsieve.db.UserMapper
import com.pennsieve.models.{ NodeCodes, Organization }
import com.pennsieve.notifications.api.NotificationWebServer.DIContainer
import com.pennsieve.aws.cognito._
import com.pennsieve.test.{
  PersistantTestContainers,
  PostgresSeedDockerContainer,
  SQSDockerContainer
}
import com.pennsieve.test.helpers.TestDatabase
import com.pennsieve.traits.PostgresProfile.api._
import akka.actor.ActorSystem
import com.dimafeng.testcontainers.{ ForAllTestContainer, MultipleContainers }
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  Suite,
  SuiteMixin
}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContextExecutor

trait NotificationsBaseSpec
    extends SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalaFutures
    with PersistantTestContainers
    with PostgresSeedDockerContainer
    with SQSDockerContainer
    with TestDatabase { self: Suite =>

  implicit val system: ActorSystem = ActorSystem("system")
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  var notificationsContainer: DIContainer = _

  // The schema for this organization exists in the seed database
  val organization: Organization = Organization(
    nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
    name = "Notifications Service Test",
    slug = "Notifications Service Test",
    encryptionKeyId = Some("test-encryption-key"),
    id = 1
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
    assert(notificationsContainer != null)

    notificationsContainer.db
      .run(for {
        _ <- UserMapper.delete
        _ <- clearOrganizationSchema(organization.id)
      } yield ())
      .await
  }

  override def afterStart(): Unit = {
    super.afterStart()

    notificationsContainer = new InsecureContainer(config)
    with InsecureCoreContainer with DatabaseContainer with LocalSQSContainer
    with LocalCognitoContainer {
      override val postgresUseSSL: Boolean = false
    }
  }

  override def afterAll(): Unit = {
    notificationsContainer.db.close()
    super.afterAll()
  }

  def config: Config = {
    ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(sqsContainer.config)
      .withValue("pennsieve.jwt.key", ConfigValueFactory.fromAnyRef("testkey"))
      .withValue("environment", ConfigValueFactory.fromAnyRef("local"))
      .withValue(
        "notifications.pingInterval",
        ConfigValueFactory.fromAnyRef(20)
      )
      .withValue(
        "notifications.freshnessThreshold",
        ConfigValueFactory.fromAnyRef(30)
      )
      .withValue(
        "notifications.aggregationCount",
        ConfigValueFactory.fromAnyRef(5)
      )
      .withValue(
        "notifications.aggregationInterval",
        ConfigValueFactory.fromAnyRef(30)
      )
      .withValue(
        "notifications.rateLimit.elements",
        ConfigValueFactory.fromAnyRef(1)
      )
      .withValue(
        "notifications.rateLimit.seconds",
        ConfigValueFactory.fromAnyRef(2)
      )
      .withValue(
        "notifications.retry.minBackoff",
        ConfigValueFactory.fromAnyRef(1)
      )
      .withValue(
        "notifications.retry.maxBackoff",
        ConfigValueFactory.fromAnyRef(3)
      )
      .withValue(
        "notifications.retry.randomFactor",
        ConfigValueFactory.fromAnyRef(0.2)
      )
      .withValue(
        "notifications.retry.maxRestarts",
        ConfigValueFactory.fromAnyRef(3)
      )
      .withValue("parallelism", ConfigValueFactory.fromAnyRef(1))
      .withValue(
        "postgres.notificationChannel",
        ConfigValueFactory.fromAnyRef("notification_service")
      )
  }
}
