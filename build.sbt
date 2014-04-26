name := "akka-rabbitmq"

organization := "com.thenewmotion.akka"

sbtVersion := "0.13.2"

version := "1.1.2-SNAPSHOT"

scalaVersion := "2.10.4"

homepage := Some(url("https://github.com/thenewmotion/akka-rabbitmq"))

licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

val akkaVersion = "2.3.2"

libraryDependencies ++= Seq(
  "com.rabbitmq" % "amqp-client" % "3.1.3",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "org.mockito" % "mockito-all" % "+"
)

pomExtra := (
  <parent>
    <groupId>ua.t3hnar</groupId>
    <artifactId>scala-parent-pom_2.10</artifactId>
    <version>2.4</version>
  </parent>
  <developers>
    <developer>
      <name>Yaroslav Klymko</name>
      <email>y.klymko@thenewmotion.com</email>
    </developer>
  </developers>
  <scm>
    <url>https://github.com/thenewmotion/akka-rabbitmq</url>
    <connection>scm:git:ssh://git@github.com/thenewmotion/akka-rabbitmq.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/thenewmotion/akka-rabbitmq.git</developerConnection>
    <tag>HEAD</tag>
  </scm>
)

publishTo := {
  val nexus = "http://nexus.thenewmotion.com/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "content/repositories/releases-public")
}

publishArtifact in Test := false
