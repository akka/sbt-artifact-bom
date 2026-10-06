ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "9.9.9-SIBLING"

val checkBom = inputKey[Unit]("Verifies whether the generated BOM includes the internal lib module")

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
      val expectInternal = sbt.complete.DefaultParsers.spaceDelimited("<include-internal>").parsed.head.toBoolean
      val bomFile = (ThisBuild / baseDirectory).value / "artifact-bom" / name.value / "pom.xml"
      assert(bomFile.exists(), s"BOM file not found at: $bomFile")
      val content = IO.read(bomFile)
      // The transitive dep brought in via the sibling 'lib' module must still appear
      assert(content.contains("com.typesafe"), "BOM must contain transitive 'com.typesafe' dependency")
      assert(content.contains("config"), "BOM must contain transitive 'config' dependency")
      val pom = scala.xml.XML.loadString(content)
      val dependencies = pom \ "dependencyManagement" \ "dependencies" \ "dependency"
      val internal = dependencies.filter(dep =>
        (dep \ "groupId").text == "com.example" && (dep \ "artifactId").text == "lib_2.13")
      assert(internal.size == (if (expectInternal) 1 else 0),
        s"Expected internal lib inclusion to be $expectInternal, got:\n$content")
      if (expectInternal) {
        assert((internal.head \ "version").text == "9.9.9-SIBLING",
          s"BOM must pin the internal module at its project version, got:\n$content")
      } else {
        assert(!content.contains("9.9.9-SIBLING"),
          s"BOM must not reference the sibling project version, got:\n$content")
      }
      streams.value.log.info(s"Multi-module BOM has expected internal module inclusion: $expectInternal")
    }
  )

lazy val root = (project in file("."))
  .aggregate(lib, app)
  .settings(name := "multi-module-root")
