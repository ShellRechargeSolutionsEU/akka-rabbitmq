organization := "com.newmotion"
name := "akka-rabbitmq"

licenses := Seq(
  ("Apache License, Version 2.0",
   url("http://www.apache.org/licenses/LICENSE-2.0")))

homepage := Some(new URL("https://github.com/NewMotion/akka-rabbitmq"))

scalaVersion := "2.13.6"

crossScalaVersions := Seq("2.13.6", "2.12.12", "3.1.0")

def akka(name: String): ModuleID = "com.typesafe.akka" %% s"akka-$name" % "2.6.+"

libraryDependencies ++= Seq(
  "com.rabbitmq" % "amqp-client" % "5.13.1",
  akka("actor") % "provided",
  akka("testkit") % "test",
  "com.typesafe" % "config" % "1.4.1" % Test,
  ("org.specs2" %% "specs2-mock" % "4.13.0" % Test).cross(CrossVersion.for3Use2_13)
)

Format.settings
