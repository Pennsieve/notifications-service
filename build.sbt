ThisBuild / resolvers ++= Seq(
  "pennsieve-maven-proxy" at "https://nexus.pennsieve.cc/repository/maven-public",
  Resolver.url("pennsieve-ivy-proxy", url("https://nexus.pennsieve.cc/repository/ivy-public/"))( Patterns("[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]") ),
  Resolver.sonatypeRepo("snapshots"),
  Resolver.bintrayRepo("commercetools", "maven")
)

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env("PENNSIEVE_NEXUS_USER"),
  sys.env("PENNSIEVE_NEXUS_PW")
)

ThisBuild / scalaVersion := "2.12.8"
ThisBuild / organization := "com.blackfynn"

// Run tests in a separate JVM to prevent resource leaks.
ThisBuild / Test / fork := true
cancelable in Global := true

ThisBuild / version := "4.1.0"

lazy val akkaVersion = "2.6.5"
lazy val akkaCirceVersion = "0.3.0"
lazy val akkaHttpVersion = "10.1.11"
lazy val akkaStreamContribVersion = "0.10"
lazy val alpakkaVersion = "2.0.1"
lazy val circeVersion = "0.11.1"
lazy val coreVersion = "50-e49ffb9"
lazy val scalatestVersion = "3.0.1"
lazy val slickVersion = "3.2.3"

lazy val `notifications-service` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .settings(
    name := "notifications-service",
    scalacOptions ++= Seq(
      "-language:postfixOps",
      "-language:implicitConversions",
      "-Xmax-classfile-name",
      "100",
      "-feature",
      "-deprecation",
      "-Ypartial-unification"
    ),
    libraryDependencies ++= Seq(
      "com.pennsieve" %% "pennsieve-core" % coreVersion,
      "com.pennsieve" %% "core-models" % coreVersion,
      "com.pennsieve" %% "bf-akka-http" % coreVersion,

      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "ch.qos.logback" % "logback-core" % "1.2.3",
      "net.logstash.logback" % "logstash-logback-encoder" % "5.2",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",

      "com.typesafe" % "config" % "1.2.1",
      "com.iheart" %% "ficus" % "1.4.0",

      "org.postgresql" % "postgresql" % "42.2.0",
      "com.typesafe.slick" %% "slick" % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,

      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,

      "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.lightbend.akka" %% "akka-stream-alpakka-sqs" % alpakkaVersion,

      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
      "org.mdedetrich" %% "akka-stream-circe" % akkaCirceVersion,
      "org.mdedetrich" %% "akka-http-circe" % akkaCirceVersion,
      "com.github.cb372" %% "scalacache-caffeine" % "0.10.0",

      "io.lettuce" % "lettuce-core" % "5.1.2.RELEASE",

      // testing deps
      "com.pennsieve" %% "pennsieve-core" % coreVersion % Test classifier "tests",
      "com.pennsieve" %% "core-models" % coreVersion % Test,
      "com.dimafeng" %% "testcontainers-scala" % "0.38.8" % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    ),
    excludeDependencies ++= Seq(
      ExclusionRule("commons-logging", "commons-logging")
    ),
    dockerfile in docker := {
      val artifact: File = assembly.value
      val artifactTargetPath = s"/app/${artifact.name}"
      new Dockerfile {
        from("pennsieve/java-cloudwrap:10-jre-slim-0.5.9")
        copy(artifact, artifactTargetPath, chown="pennsieve:pennsieve")
        run("mkdir", "-p", "/home/pennsieve/.postgresql")
        run("wget", "-qO", "/home/pennsieve/.postgresql/root.crt", "https://s3.amazonaws.com/rds-downloads/rds-ca-2019-root.pem")
        cmd(
          "--service",
          "notifications-service",
          "exec",
          "java",
          "-jar",
          artifactTargetPath
        )
      }
    },
    imageNames in docker := Seq(
      ImageName("pennsieve/notifications-service:latest")
    ),
    test in assembly := {},
    assemblyMergeStrategy in assembly := {
      case PathList("OSGI-OPT", _ @_*) => MergeStrategy.last
      case PathList("META-INF", _ @ _*) => MergeStrategy.discard
      case PathList("codegen-resources", "customization.config", _ @_*) => MergeStrategy.discard
      case PathList("codegen-resources", "examples-1.json", _ @_*) => MergeStrategy.discard
      case PathList("codegen-resources", "paginators-1.json", _ @_*) => MergeStrategy.discard
      case PathList("codegen-resources", "service-2.json", _ @_*) => MergeStrategy.discard
      case PathList("codegen-resources", "waiters-2.json", _ @_*) => MergeStrategy.discard
      case PathList("mime.types") => MergeStrategy.last
      case PathList("module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    headerLicense :=  Some(HeaderLicense.Custom(
      "Copyright (c) 2019 University of Pennsylvania All Rights Reserved."
    )),
    headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
    scalafmtOnCompile := true,
    coverageExcludedPackages :=
      """
       | com.blackfynn.notifications.api.NotificationWebServer;
      """.stripMargin.replace("\n", ""),
    coverageMinimum := 25,
    coverageFailOnMinimum := true
  )

lazy val root = (project in file("."))
.aggregate(`notifications-service`)
