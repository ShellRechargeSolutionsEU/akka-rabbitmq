import sbt._
import Keys._
import sbtrelease.ReleasePlugin._

object Build extends Build {
  lazy val basicSettings = Seq(
    name := "akka-rabbitmq",
    organization := "com.thenewmotion.akka",
    scalaVersion := "2.10.4",
    crossScalaVersions := Seq("2.10.4", "2.11.0"),
    licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    homepage := Some(new URL("https://github.com/thenewmotion/akka-rabbitmq")),
    scalacOptions := Seq("-encoding", "UTF-8", "-unchecked", "-deprecation", "-feature"),
    libraryDependencies ++= Seq(akkaActor, amqpClient, akkaTestkit, junit, mockito, specs2))

  val akkaVersion = "2.3.6"

  lazy val akkaActor    = "com.typesafe.akka" %% "akka-actor" % akkaVersion
  lazy val amqpClient   = "com.rabbitmq" % "amqp-client" % "3.3.5"
  lazy val akkaTestkit  = "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
  lazy val junit        = "junit" % "junit" % "4.11" % "test"
  lazy val mockito      = "org.mockito" % "mockito-all" % "1.9.5" % "test"
  lazy val specs2       = "org.specs2" %% "specs2" % "2.3.11" % "test"

  lazy val root = Project(
    "akka-rabbitmq",
    file("."),
    settings = basicSettings ++ Defaults.defaultSettings ++ releaseSettings ++ Publish.settings ++ Format.settings)
}
