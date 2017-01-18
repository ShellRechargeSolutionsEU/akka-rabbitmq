organization := "com.thenewmotion"
name := "akka-rabbitmq"

enablePlugins(OssLibPlugin)

licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
homepage := Some(new URL("https://github.com/NewMotion/akka-rabbitmq"))

def akka(scalaVersion: String) = {
  val version = "2.4.14"

  def libs(xs: String*) = xs.map(x => "com.typesafe.akka" %% s"akka-$x" % version)

  libs("actor") ++ libs("testkit").map(_ % "test")
}

libraryDependencies ++= {
  akka(scalaVersion.value) ++
  Seq(
    "com.rabbitmq" % "amqp-client" % "4.0.0",
    "com.typesafe" % "config" % "1.3.1" % "test",
    "org.specs2" %% "specs2-mock" % "3.8.6" % "test"
  )
}

Format.settings

