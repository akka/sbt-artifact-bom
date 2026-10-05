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
      val pom = scala.xml.XML.loadString(content)
      val includeDependencies = makeBomIncludeDependencies.value
      assert((pom \ "dependencyManagement").nonEmpty == !includeDependencies,
        s"dependencyManagement presence must be ${!includeDependencies}, got:\n$content")
      val dependencies = if (includeDependencies) pom \ "dependencies" \ "dependency"
        else pom \ "dependencyManagement" \ "dependencies" \ "dependency"
      assert(dependencies.exists(dep => (dep \ "artifactId").text == "config"),
        s"dependency listing must include config, got:\n$content")
      assert((pom \ "dependencies").nonEmpty == includeDependencies,
        s"top-level dependencies presence must be $includeDependencies, got:\n$content")
      streams.value.log.info(s"BOM has the expected $expected <dependencies> block(s)")
    }
  )
