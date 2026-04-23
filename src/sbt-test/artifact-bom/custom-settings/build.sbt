ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkBom = taskKey[Unit]("Verifies content of the generated BOM file with custom settings")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "custom-app",
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    makeBomTargetName := "my-bom",
    makeBomProjectVersion := "42.0.0",
    checkBom := {
      val bomFile = baseDirectory.value / "my-bom" / name.value / "pom.xml"
      assert(bomFile.exists(), s"BOM file not found at: $bomFile")
      val content = IO.read(bomFile)
      assert(content.contains("42.0.0"), "BOM must contain the custom project version")
      assert(!content.contains("100.0.0"), "BOM must not contain the default version when overridden")
      streams.value.log.info("Custom-settings BOM content verified successfully")
    }
  )
