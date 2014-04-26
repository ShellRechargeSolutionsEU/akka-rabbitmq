import sbt._
import Keys._

object Publish {
  lazy val publishSetting = publishTo <<= version.apply {
    v =>
      val nexus = "http://nexus.thenewmotion.com/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots-public")
      else
        Some("releases" at nexus + "content/repositories/releases-public")
  }

  lazy val settings = Seq(
    publishSetting,
    pomExtra := (
      <scm>
        <url>git@github.com:thenewmotion/akka-rabbitmq.git</url>
        <connection>scm:git:git@github.com:thenewmotion/akka-rabbitmq.git</connection>
      </scm>
        <developers>
          <developer>
            <id>t3hnar</id>
            <name>Yaroslav Klymko</name>
            <email>t3hnar@gmail.com</email>
          </developer>
        </developers>),
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := {_ => false})
}
