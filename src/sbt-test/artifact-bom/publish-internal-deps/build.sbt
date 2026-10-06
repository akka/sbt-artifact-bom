ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"
ThisBuild / version := "2.0.0-INTERNAL"

val checkDiskFormat = taskKey[Unit]("Verifies dependencies-only on-disk output includes the internal sibling")

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
    checkDiskFormat := {
      makeBom.value
      val pomFile = (ThisBuild / baseDirectory).value / "artifact-bom" / name.value / "pom.xml"
      val pom = scala.xml.XML.loadFile(pomFile)
      assert((pom \ "dependencyManagement").isEmpty, s"Expected dependencies-only format: $pom")
      val internal = (pom \ "dependencies" \ "dependency").filter(dep =>
        (dep \ "groupId").text == "com.example" && (dep \ "artifactId").text == "internal-lib")
      assert(internal.size == 1 && (internal.head \ "version").text == "2.0.0-INTERNAL",
        s"On-disk POM must pin the internal sibling: $pom")
      assert((pom \ "dependencies" \ "dependency").exists(dep => (dep \ "artifactId").text == "config"),
        s"On-disk POM must include external transitive dependencies: $pom")
    },
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
      val published = scala.xml.XML.loadString(content)
      assert((published \ "dependencies").isEmpty,
        s"Published BOM must not have top-level dependencies, got:\n$content")
      streams.value.log.info("BOM correctly pins the internal sibling module")
    }
  )

lazy val root = (project in file("."))
  .aggregate(internalLib, bom)
  .settings(name := "internal-deps-root")
