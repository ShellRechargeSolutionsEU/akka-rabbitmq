import sbt._
import Keys._
import sbtrelease.ReleasePlugin._

object Build extends Build {
  val basicSettings = Seq(
    name := "akka-rabbitmq",
    organization := "com.thenewmotion.akka",
    scalaVersion := "2.10.4",
    crossScalaVersions := Seq("2.10.4", "2.11.4"),
    licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    homepage := Some(new URL("https://github.com/thenewmotion/akka-rabbitmq")),
    scalacOptions := Seq("-encoding", "UTF-8", "-unchecked", "-deprecation", "-feature"),
    libraryDependencies ++= Seq(akkaActor, amqpClient, akkaTestkit, specs2JUnit, specs2Mock))

  val akkaVersion = "2.3.7"

  val akkaActor   = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
  val amqpClient  = "com.rabbitmq" % "amqp-client" % "3.4.2"
  val specs2JUnit = "org.specs2" %% "specs2-junit" % "2.4.15" % "test"
  val specs2Mock  = "org.specs2" %% "specs2-mock" % "2.4.15" % "test"

  val root = Project(
    "akka-rabbitmq",
    file("."),
    settings = basicSettings ++ Defaults.defaultSettings ++ releaseSettings ++ Publish.settings ++ Format.settings)
}
