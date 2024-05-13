resolvers ++= Seq(
  "Pennsieve Public" at "https://nexus.pennsieve.cc/repository/maven-public",
  Resolver.bintrayIvyRepo("rallyhealth", "sbt-plugins")
)

credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env.getOrElse("PENNSIEVE_NEXUS_USER", "pennsieve-ci"),
  sys.env.getOrElse("PENNSIEVE_NEXUS_PW", "")
)

addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.11.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")

addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.6.0-RC4")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
