pomExtra in Global := {
  <scm>
    <connection>scm:git:git@github.com:thenewmotion/akka-rabbitmq.git</connection>
    <developerConnection>scm:git:git@github.com:thenewmotion/akka-rabbitmq.git</developerConnection>
    <url>git@github.com:thenewmotion/akka-rabbitmq.git</url>
  </scm>
  <developers>
    <developer>
      <id>gertjana</id>
      <name>Gertjan Assies</name>
      <url>https://github.com/gertjana</url>
    </developer>
    <developer>
      <id>reinierl</id>
      <name>Reinier Lamers</name>
      <url>https://github.com/reinierl</url>
    </developer>
    <developer>
      <id>t3hnar</id>
      <name>Yaroslav Klymko</name>
      <url>https://github.com/t3hnar</url>
    </developer>
  </developers>
}

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
