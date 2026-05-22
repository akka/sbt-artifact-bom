ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val countDependencies = inputKey[Unit]("Asserts the number of <dependencies> blocks in the generated BOM")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "sample-app",
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    countDependencies := {
      val expected = sbt.complete.DefaultParsers.spaceDelimited("<n>").parsed.head.toInt
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val content = IO.read(bomFile)
      val actual = "<dependencies>".r.findAllIn(content).size
      assert(actual == expected, s"expected $expected <dependencies> block(s), got $actual in:\n$content")
      // dependencyManagement must always be present
      assert(content.contains("<dependencyManagement>"), s"BOM must always contain dependencyManagement, got:\n$content")
      streams.value.log.info(s"BOM has the expected $expected <dependencies> block(s)")
    }
  )
