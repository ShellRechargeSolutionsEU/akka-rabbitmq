organization := "com.newmotion"
name := "akka-rabbitmq"

enablePlugins(OssLibPlugin)

licenses := Seq(
  ("Apache License, Version 2.0",
   url("http://www.apache.org/licenses/LICENSE-2.0")))

homepage := Some(new URL("https://github.com/NewMotion/akka-rabbitmq"))

scalaVersion := tnm.ScalaVersion.curr

crossScalaVersions := Seq(tnm.ScalaVersion.curr, tnm.ScalaVersion.prev)

def akka(name: String): ModuleID = "com.typesafe.akka" %% s"akka-$name" % "2.6.+"

libraryDependencies ++= Seq(
  "com.rabbitmq" % "amqp-client" % "5.10.0",
  akka("actor") % "provided",
  akka("testkit") % "test",
  "com.typesafe" % "config" % "1.4.1" % "test",
  "org.specs2" %% "specs2-mock" % "4.10.3" % "test"
)

Format.settings
