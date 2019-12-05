organization := "com.newmotion"
name := "akka-rabbitmq"

enablePlugins(OssLibPlugin)

licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
homepage := Some(new URL("https://github.com/NewMotion/akka-rabbitmq"))

scalaVersion := tnm.ScalaVersion.prev

crossScalaVersions := Seq(tnm.ScalaVersion.aged, tnm.ScalaVersion.curr, tnm.ScalaVersion.prev)

def akka(scalaVersion: String) = {
  val version = "2.5.+"

  def libs(xs: String*) = xs.map(x => "com.typesafe.akka" %% s"akka-$x" % version)

  libs("actor").map(_ % "provided") ++ libs("testkit").map(_ % "test")
}

libraryDependencies ++= {
  akka(scalaVersion.value) ++
  Seq(
    "com.rabbitmq" % "amqp-client" % "5.7.3",
    "com.typesafe" % "config" % "1.4.0" % "test",
    "org.specs2" %% "specs2-mock" % "4.8.1" % "test"
  )
}

Format.settings

