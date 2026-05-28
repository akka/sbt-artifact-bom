ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "2.0.0-INTERNAL"

val checkBomContent = taskKey[Unit]("Verifies the published BOM pins the internal sibling and its transitive deps")

lazy val internalLib = (project in file("lib"))
  .settings(
    name := "internal-lib",
    crossPaths := false,
    libraryDependencies += "com.typesafe" % "config" % "1.4.3"
  )

lazy val bom = (project in file("bom"))
  .enablePlugins(ArtifactBomPlugin)
  .dependsOn(internalLib)
  .settings(bomPublishSettings)
  .settings(
    name := "example-dependencies",
    crossPaths := false,
    checkBomContent := {
      val pom = makePom.value
      val content = IO.read(pom)
      // The internal sibling module must be pinned in the BOM...
      assert(content.contains("<artifactId>internal-lib</artifactId>"),
        s"BOM must include the internal sibling module, got:\n$content")
      assert(content.contains("<version>2.0.0-INTERNAL</version>"),
        s"BOM must pin the internal module at the build version, got:\n$content")
      // ...along with its transitive third-party dependency.
      assert(content.contains("com.typesafe"), s"BOM must include transitive deps, got:\n$content")
      assert(content.contains("<dependencyManagement>"), s"BOM must use dependencyManagement, got:\n$content")
      streams.value.log.info("BOM correctly pins the internal sibling module")
    }
  )

lazy val root = (project in file("."))
  .aggregate(internalLib, bom)
  .settings(name := "internal-deps-root")
