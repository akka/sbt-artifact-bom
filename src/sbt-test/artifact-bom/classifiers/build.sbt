ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "com.example"

val checkClassifiers = taskKey[Unit]("Verifies the classifier entries of the generated BOM file")
val snapshotBom = taskKey[Unit]("Copies the generated BOM aside for a later byte comparison")
val checkBomIdentical = taskKey[Unit]("Asserts the regenerated BOM is byte-identical to the snapshot")
val deleteBomCache = taskKey[Unit]("Deletes the makeBom cache file under streams")

lazy val root = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
  .settings(
    name := "sample-app",

    // this module publishes no unclassified jar, only a pom plus per-platform classified jars,
    // so it also covers the pom-only case
    libraryDependencies += "io.netty" % "netty-transport-native-epoll" % "4.1.136.Final" classifier "linux-x86_64",
    // resolves the sources and javadoc jars too, so the BOM must filter those classifiers out
    libraryDependencies += "com.typesafe" % "config" % "1.4.3" withSources () withJavadoc (),

    checkClassifiers := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val content = IO.read(bomFile)
      val pom = scala.xml.XML.loadString(content)

      val managed = pom \ "dependencyManagement" \ "dependencies" \ "dependency"
      def classifiersOf(artifact: String): Seq[String] =
        managed
          .filter(d => (d \ "artifactId").text == artifact)
          .map(d => (d \ "classifier").text)

      val epoll = classifiersOf("netty-transport-native-epoll")
      assert(epoll == Seq("", "linux-x86_64"),
        s"expected an unclassified entry followed by linux-x86_64, got $epoll in:\n$content")

      val epollVersions = managed
        .filter(d => (d \ "artifactId").text == "netty-transport-native-epoll")
        .map(d => (d \ "version").text)
        .distinct
      assert(epollVersions == Seq("4.1.136.Final"),
        s"expected every netty-transport-native-epoll entry at 4.1.136.Final, got $epollVersions")

      val docClassifiers = managed.map(d => (d \ "classifier").text).filter(Set("sources", "javadoc"))
      assert(docClassifiers.isEmpty,
        s"BOM must not manage sources or javadoc artifacts, got $docClassifiers in:\n$content")

      streams.value.log.info("BOM classifier entries verified successfully")
    },

    snapshotBom := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      IO.write(target.value / "bom-snapshot.xml", IO.read(bomFile))
    },

    checkBomIdentical := {
      val bomFile = baseDirectory.value / "artifact-bom" / name.value / "pom.xml"
      val previous = IO.read(target.value / "bom-snapshot.xml")
      val current = IO.read(bomFile)
      assert(current == previous, s"BOM is not stable across runs:\nprevious:\n$previous\ncurrent:\n$current")
      streams.value.log.info("BOM is byte-identical across runs")
    },

    deleteBomCache := {
      val streamsDir = target.value / "streams"
      val cacheFiles = (streamsDir ** "makeBom.cachekey").get
      cacheFiles.foreach(IO.delete)
      assert(cacheFiles.nonEmpty, s"Expected to find a makeBom.cachekey under $streamsDir")
      streams.value.log.info(s"Deleted ${cacheFiles.size} makeBom cache file(s)")
    }
  )
