ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkBom = taskKey[Unit]("Verifies content of the generated BOM file")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "sample-app",
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    checkBom := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      assert(bomFile.exists(), s"BOM file not found at: $bomFile")
      val content = IO.read(bomFile)
      assert(content.contains("com.example"), "BOM must contain the project groupId")
      assert(content.contains("sample-app"), "BOM must contain the project artifactId")
      assert(content.contains("100.0.0"), "BOM must contain the default BOM version")
      assert(content.contains("com.typesafe"), "BOM must contain the com.typesafe dependency")
      assert(content.contains("config"), "BOM must contain the config dependency")
      streams.value.log.info("BOM content verified successfully")
    }
  )
