ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkBom = taskKey[Unit]("Verifies content of the generated BOM file")
val recordBomMtime = taskKey[Unit]("Records the BOM file's last modified time for later comparison")
val checkBomNotRegenerated = taskKey[Unit]("Asserts the BOM file's mtime matches the previously recorded value")
val deleteBomCache = taskKey[Unit]("Deletes the makeBom cache file under streams")

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
    },
    recordBomMtime := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val mtimeFile = target.value / "bom-mtime.txt"
      IO.write(mtimeFile, bomFile.lastModified().toString)
      streams.value.log.info(s"Recorded BOM mtime: ${bomFile.lastModified()}")
    },
    checkBomNotRegenerated := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val mtimeFile = target.value / "bom-mtime.txt"
      val previous = IO.read(mtimeFile).toLong
      val current = bomFile.lastModified()
      assert(current == previous, s"BOM was rewritten unexpectedly (previous=$previous, current=$current)")
      streams.value.log.info("BOM correctly not rewritten")
    },
    deleteBomCache := {
      // Find and delete any makeBom.cachekey file under target/streams
      val streamsDir = target.value / "streams"
      val cacheFiles = (streamsDir ** "makeBom.cachekey").get
      cacheFiles.foreach(IO.delete)
      streams.value.log.info(s"Deleted ${cacheFiles.size} makeBom cache file(s)")
      assert(cacheFiles.nonEmpty, s"Expected to find a makeBom.cachekey under $streamsDir")
    }
  )
