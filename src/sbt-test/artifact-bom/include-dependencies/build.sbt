ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkBomFormat = taskKey[Unit]("Verifies the generated POM uses the selected dependency format")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "sample-app",
    libraryDependencies += "com.typesafe" % "config" % "1.4.3",
    checkBomFormat := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val content = IO.read(bomFile)
      val pom = scala.xml.XML.loadString(content)
      val includeDependencies = makeBomIncludeDependencies.value
      assert((pom \ "dependencyManagement").nonEmpty == !includeDependencies,
        s"dependencyManagement presence must be ${!includeDependencies}, got:\n$content")
      assert((pom \ "dependencies").nonEmpty == includeDependencies,
        s"top-level dependencies presence must be $includeDependencies, got:\n$content")
      val dependencies =
        if (includeDependencies) pom \ "dependencies" \ "dependency"
        else pom \ "dependencyManagement" \ "dependencies" \ "dependency"
      assert(dependencies.exists(dep => (dep \ "artifactId").text == "config"),
        s"dependency listing must include config, got:\n$content")
      streams.value.log.info(s"POM has the expected format with includeDependencies=$includeDependencies")
    }
  )
