organization := "com.thenewmotion.akka"
name := "akka-rabbitmq"

enablePlugins(OssLibPlugin)

licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
homepage := Some(new URL("https://github.com/thenewmotion/akka-rabbitmq"))

libraryDependencies ++= {
  val akkaVersion = "2.3.12"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.rabbitmq" % "amqp-client" % "3.4.2",

    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe" % "config" % "1.3.0" % "test",
    "org.specs2" %% "specs2-mock" % "2.4.17" % "test"
  )
}

Format.settings

