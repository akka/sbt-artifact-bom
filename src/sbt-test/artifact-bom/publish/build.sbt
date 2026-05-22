ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "1.2.3"

val checkArtifacts = taskKey[Unit]("Verifies the BOM is published alongside the normal jar")
val checkNoBomWhenDisabled = taskKey[Unit]("Verifies no BOM artifact is published when opt-in is off")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "publishable-bom",
    crossPaths := false,
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    makeBomPublish := true,
    checkArtifacts := {
      val arts = packagedArtifacts.value.keys.toList
      // The BOM is published as a classified pom...
      val bom = arts.find(a => a.classifier.contains("bom") && a.`type` == "pom")
        .getOrElse(sys.error(s"expected a classified BOM pom artifact, got: $arts"))
      // ...and the project's normal jar is still published.
      assert(arts.exists(a => a.extension == "jar" && a.classifier.isEmpty),
        s"expected the main jar to still be published, got: $arts")

      val content = IO.read(makeBomArtifact.value)
      assert(content.contains("<packaging>pom</packaging>"), s"BOM must be pom-packaged, got:\n$content")
      assert(content.contains("<dependencyManagement>"), s"BOM must use dependencyManagement, got:\n$content")
      assert(content.contains("<version>1.2.3</version>"), s"BOM must use the real project version, got:\n$content")
      assert(!content.contains("100.0.0"), s"BOM must not use the on-disk placeholder version, got:\n$content")
      assert(content.contains("com.typesafe"), s"BOM must contain dependencies, got:\n$content")
      streams.value.log.info("Published BOM artifact verified successfully")
    },
    checkNoBomWhenDisabled := {
      val arts = packagedArtifacts.value.keys.toList
      assert(!arts.exists(_.classifier.contains("bom")),
        s"no BOM artifact should be published when makeBomPublish := false, got: $arts")
    }
  )
