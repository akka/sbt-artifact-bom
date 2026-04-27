ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "9.9.9-SIBLING"

val checkBom = taskKey[Unit]("Verifies content of the generated BOM file for the app module")

lazy val lib = (project in file("lib"))
  .settings(
    name := "lib",
    libraryDependencies += "com.typesafe" % "config" % "1.4.3"
  )

lazy val app = (project in file("app"))
  .enablePlugins(ArtifactBomPlugin)
  .dependsOn(lib)
  .settings(
    name := "app",
    checkBom := {
      val bomFile = (ThisBuild / baseDirectory).value / "artifact-bom" / name.value / "pom.xml"
      assert(bomFile.exists(), s"BOM file not found at: $bomFile")
      val content = IO.read(bomFile)
      // The transitive dep brought in via the sibling 'lib' module must still appear
      assert(content.contains("com.typesafe"), "BOM must contain transitive 'com.typesafe' dependency")
      assert(content.contains("config"), "BOM must contain transitive 'config' dependency")
      // The sibling project itself must not appear, otherwise its release version would churn the BOM
      assert(!content.contains("9.9.9-SIBLING"), s"BOM must not reference the sibling project version, got:\n$content")
      assert(!content.contains("<artifactId>lib</artifactId>"), s"BOM must not list the sibling 'lib' module, got:\n$content")
      streams.value.log.info("Multi-module BOM correctly excludes sibling project")
    }
  )

lazy val root = (project in file("."))
  .aggregate(lib, app)
  .settings(name := "multi-module-root")
