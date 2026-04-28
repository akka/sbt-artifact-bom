ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkBomScalaSuffix = inputKey[Unit]("Asserts the generated BOM contains the expected Scala suffix in scala-library coordinates")
val checkBomMissing = taskKey[Unit]("Asserts the BOM file does not exist")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "cross-app",
    crossScalaVersions := Seq("2.13.16", "3.3.4"),
    checkBomScalaSuffix := {
      val args = sbt.complete.DefaultParsers.spaceDelimited("<suffix>").parsed
      val expected = args.headOption.getOrElse(sys.error("expected one argument: scala suffix"))
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      assert(bomFile.exists(), s"BOM file not found at: $bomFile")
      val content = IO.read(bomFile)
      assert(content.contains(expected), s"BOM expected to contain '$expected', got:\n$content")
      streams.value.log.info(s"BOM contains expected suffix '$expected'")
    },
    checkBomMissing := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      assert(!bomFile.exists(), s"BOM file should not exist at: $bomFile")
    }
  )
