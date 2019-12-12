pomExtra in Global := {
  <developers>
    <developer>
      <id>sbmpost</id>
      <name>Stefan Post</name>
      <url>https://github.com/sbmpost</url>
    </developer>
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

releasePublishArtifactsAction := PgpKeys.publishSigned.value