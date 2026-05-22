ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "4.5.6"

val checkArtifacts = taskKey[Unit]("Verifies the module publishes a BOM pom and no jar")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(bomPublishSettings)
  .settings(
    name := "example-dependencies",
    crossPaths := false,
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    checkArtifacts := {
      val arts = packagedArtifacts.value
      // No jar/sources/docs are published for a BOM-only module...
      assert(!arts.keys.exists(_.extension == "jar"),
        s"BOM-only module must not publish any jar, got: ${arts.keys.toList}")
      // ...and the module's main pom IS the BOM.
      val pom = arts.collectFirst { case (a, f) if a.`type` == "pom" => f }
        .getOrElse(sys.error(s"expected a pom artifact, got: ${arts.keys.toList}"))
      val content = IO.read(pom)
      assert(content.contains("<packaging>pom</packaging>"), s"pom must be pom-packaged, got:\n$content")
      assert(content.contains("<dependencyManagement>"), s"pom must use dependencyManagement, got:\n$content")
      assert(content.contains("<artifactId>example-dependencies</artifactId>"), s"pom must use the module artifactId, got:\n$content")
      assert(content.contains("<version>4.5.6</version>"), s"pom must use the real project version, got:\n$content")
      assert(content.contains("com.typesafe"), s"pom must contain dependencies, got:\n$content")
      streams.value.log.info("BOM-only module verified successfully")
    }
  )
