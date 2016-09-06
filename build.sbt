organization := "com.thenewmotion"
name := "akka-rabbitmq"

enablePlugins(OssLibPlugin)

licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
homepage := Some(new URL("https://github.com/thenewmotion/akka-rabbitmq"))

javaVersion := "1.7"

def akka(scalaVersion: String) = {
  val version = scalaVersion match {
    case x if x.startsWith("2.11") => "2.4.9"
    case x if x.startsWith("2.10") => "2.3.15"
    case other => throw new Exception(s"Unsupported scala version $other")
  }

  def libs(xs: String*) = xs.map(x => "com.typesafe.akka" %% s"akka-$x" % version)

  libs("actor") ++ libs("testkit").map(_ % "test")
}

libraryDependencies ++= {
  akka(scalaVersion.value) ++
  Seq(
    "com.rabbitmq" % "amqp-client" % "3.6.5",
    "com.typesafe" % "config" % "1.3.0" % "test",
    "org.specs2" %% "specs2-mock" % "3.8.4" % "test"
  )
}

Format.settings

