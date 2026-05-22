package io.akka.sbt

import sbt._
import Keys._
import scala.xml.PrettyPrinter

object ArtifactBomPlugin extends AutoPlugin {

  override def trigger = noTrigger

  object autoImport {
    val makeBom = taskKey[Unit]("Generates a Bill of Material in the form of a Maven POM file including all direct and indirect dependencies (according to sbt)")
    val makeBomTargetDir = settingKey[File]("The directory where BOM directory are stored (defaults to baseDirectory)")
    val makeBomTargetName = settingKey[String]("The name of the directory where BOM files are stored (defaults to artifact-bom)")
    val makeBomProjectVersion = settingKey[String]("Project version of the BOM written to disk (defaults to fixed string to avoid versioning trouble). Publication always uses the real project version.")
    val makeBomScalaVersion = settingKey[Option[String]]("If set, makeBom only runs when scalaVersion matches this value. Useful for cross-built projects to avoid the BOM contents flipping between cross-build passes (defaults to the head of crossScalaVersions, i.e. the project's primary Scala version)")
    val makeBomOnCompile = settingKey[Boolean]("If true (default), makeBom is triggered automatically after compile. Disable for release flows that must keep the working copy clean (e.g. to avoid disturbing dynver)")
    val makeBomPublish = settingKey[Boolean]("If true, the BOM is published as an additional classified pom artifact alongside the project's normal artifacts (defaults to false)")
    val makeBomClassifier = settingKey[String]("Classifier used for the published BOM artifact (defaults to bom)")
    val makeBomArtifact = taskKey[File]("Generates the BOM pom file used for publication (uses the real project version)")
    val makeBomIncludeDependencies = settingKey[Boolean]("If true, the generated pom also populates a top-level <dependencies> section (in addition to <dependencyManagement>). For backwards compatibility with consumers that expected the old dependencies-only output (defaults to false)")

    // Settings for a dedicated BOM-only module: the BOM becomes the module's main published pom,
    // with no jar/sources/docs. Apply via `.settings(bomOnlySettings)` in addition to enabling the
    // plugin. Use this when the module's sole purpose is to publish a BOM (e.g. `acme-dependencies`).
    // For a module that also ships a jar, set `makeBomPublish := true` instead to attach the BOM as
    // an extra classified artifact.
    lazy val bomOnlySettings: Seq[Setting[_]] = Seq(
      Compile / packageBin / publishArtifact := false,
      Compile / packageDoc / publishArtifact := false,
      Compile / packageSrc / publishArtifact := false,
      publishMavenStyle := true,
      makePom := {
        val content = renderBom(
          update.value,
          projectID.all(ScopeFilter(inAnyProject)).value,
          projectID.value,
          organization.value,
          version.value,
          scalaVersion.value,
          scalaBinaryVersion.value,
          makeBomIncludeDependencies.value)
        val pomFile = (makePom / artifactPath).value
        IO.write(pomFile, content)
        streams.value.log.info(s"[${name.value}] Wrote BOM as the module's published pom to ${pomFile.getAbsolutePath}")
        pomFile
      }
    )
  }

  import autoImport._

  // Internal task that hangs the triggeredBy(compile) relationship off the setting,
  // so users can disable the auto-trigger via `makeBomOnCompile := false` while still
  // being able to invoke `makeBom` explicitly.
  private val makeBomCompileTrigger = taskKey[Unit]("Internal: conditionally invokes makeBom after compile based on makeBomOnCompile").withRank(KeyRanks.Invisible)

  // Apply sbt's cross-version mapping to an un-cross-versioned module name so it matches the
  // resolved/published artifact name (e.g. lib -> lib_2.13).
  private def crossed(id: ModuleID, scalaFullVersion: String, scalaBinVersion: String): String =
    CrossVersion(id.crossVersion, scalaFullVersion, scalaBinVersion).fold(id.name)(_(id.name))

  // The (organization, crossed-name) of every project in the build. These are excluded from the
  // BOM: sibling modules get a new version on every release, which would churn the BOM contents.
  private def siblingKeys(ids: Seq[ModuleID], scalaFullVersion: String, scalaBinVersion: String): Set[(String, String)] =
    ids.map(id => (id.organization, crossed(id, scalaFullVersion, scalaBinVersion))).toSet

  // The flattened, de-duplicated, sorted set of dependencies as (group, artifact, version) tuples,
  // covering everything resolved across the 'compile' and 'runtime' configurations.
  private def bomDependencies(report: UpdateReport, siblings: Set[(String, String)]): Seq[(String, String, String)] = {
    val allModules = report.configuration(ConfigRef("compile")).toSeq.flatMap(_.modules) ++
      report.configuration(ConfigRef("runtime")).toSeq.flatMap(_.modules)

    allModules
      .groupBy(m => (m.module.organization, m.module.name))
      .map(_._2.head) // De-duplicate
      .toSeq
      .filterNot(m => siblings.contains((m.module.organization, m.module.name)))
      .map(m => (m.module.organization, m.module.name, m.module.revision))
      .sortBy(t => (t._1, t._2))
  }

  // The artifact descriptor for the published BOM: the project's main artifact name, emitted as a
  // classified pom so it sits alongside (not in place of) the normal jar/pom.
  private def bomArtifact(mainArtifact: Artifact, classifier: String): Artifact =
    mainArtifact.withType("pom").withExtension("pom").withClassifier(Some(classifier)).withConfigurations(Vector.empty)

  // Render a true BOM: a pom-packaged artifact whose dependencyManagement section pins every
  // transitive dependency, so downstream projects can import it. When includeDependencies is set,
  // the same set is also emitted as a top-level <dependencies> section for backwards compatibility.
  private def bomPom(org: String, artId: String, version: String, deps: Seq[(String, String, String)], includeDependencies: Boolean): String = {
    val dependencyEntries = deps.map { case (g, a, v) =>
      <dependency>
        <groupId>{g}</groupId>
        <artifactId>{a}</artifactId>
        <version>{v}</version>
      </dependency>
    }
    val pomXml =
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>{org}</groupId>
        <artifactId>{artId}</artifactId>
        <version>{version}</version>
        <packaging>pom</packaging>
        <dependencyManagement>
          <dependencies>
            {dependencyEntries}
          </dependencies>
        </dependencyManagement>
        {if (includeDependencies) <dependencies>{dependencyEntries}</dependencies> else scala.xml.NodeSeq.Empty}
      </project>

    new PrettyPrinter(120, 4).format(pomXml)
  }

  // Render the publishable BOM for the current project: coordinates mirror what sbt deploys
  // (organization, cross-versioned name, real project version), with siblings excluded.
  private def renderBom(report: UpdateReport, allIds: Seq[ModuleID], projectId: ModuleID,
                        org: String, version: String, scalaFullVersion: String, scalaBinVersion: String,
                        includeDependencies: Boolean): String = {
    val siblings = siblingKeys(allIds, scalaFullVersion, scalaBinVersion)
    val deps = bomDependencies(report, siblings)
    val artId = crossed(projectId, scalaFullVersion, scalaBinVersion)
    bomPom(org, artId, version, deps, includeDependencies)
  }

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    makeBomTargetDir := (ThisBuild / baseDirectory).value,
    makeBomTargetName := "artifact-bom",
    makeBomProjectVersion := "100.0.0",
    makeBomScalaVersion := crossScalaVersions.value.headOption,
    makeBomOnCompile := true,
    makeBomPublish := false,
    makeBomClassifier := "bom",
    makeBomIncludeDependencies := false,

    // Generate the BOM as a standalone pom file for publication. Coordinates mirror what sbt deploys
    // (organization, cross-versioned name, real project version). Unlike the on-disk makeBom file,
    // publication uses the real project version rather than the fixed makeBomProjectVersion placeholder.
    makeBomArtifact := {
      val scalaFullVersion = scalaVersion.value
      val scalaBinVersion = scalaBinaryVersion.value
      val artId = crossed(projectID.value, scalaFullVersion, scalaBinVersion)
      val content = renderBom(
        update.value,
        projectID.all(ScopeFilter(inAnyProject)).value,
        projectID.value,
        organization.value,
        version.value,
        scalaFullVersion,
        scalaBinVersion,
        makeBomIncludeDependencies.value)
      val pomFile = crossTarget.value / s"$artId-${version.value}-${makeBomClassifier.value}.pom"
      IO.write(pomFile, content)
      streams.value.log.info(s"[${name.value}] Wrote publishable BOM pom to ${pomFile.getAbsolutePath}")
      pomFile
    },

    // When opted in, attach the BOM as an extra classified pom artifact. This is additive: the
    // project's normal jar/sources/docs/pom are still published unchanged.
    artifacts := {
      val prev = artifacts.value
      if (makeBomPublish.value) prev :+ bomArtifact((Compile / packageBin / artifact).value, makeBomClassifier.value)
      else prev
    },
    packagedArtifacts := Def.taskDyn {
      val prev = packagedArtifacts.value
      if (makeBomPublish.value) {
        val art = bomArtifact((Compile / packageBin / artifact).value, makeBomClassifier.value)
        Def.task(prev.updated(art, makeBomArtifact.value))
      } else Def.task(prev)
    }.value,

    makeBom := Def.task {
      val s = streams.value
      val log = s.log
      val artName = name.value
      val pinnedScala = makeBomScalaVersion.value
      val currentScala = scalaVersion.value
      val report = update.value
      val org = organization.value
      val bomVersion = makeBomProjectVersion.value
      val sbv = scalaBinaryVersion.value
      val includeDeps = makeBomIncludeDependencies.value
      val allProjectIDs = projectID.all(ScopeFilter(inAnyProject)).value
      val targetDir = makeBomTargetDir.value
      val targetName = makeBomTargetName.value
      if (pinnedScala.exists(_ != currentScala)) {
        log.debug(s"[$artName] Skipping artifact BOM generation, scalaVersion $currentScala does not match pinned ${pinnedScala.get}")
      } else {
        val siblings = siblingKeys(allProjectIDs, currentScala, sbv)

        val outputFolder = targetDir / targetName / artName
        val outFile = outputFolder / "pom.xml"

        val uniqueDeps = bomDependencies(report, siblings)

        // Cache key covers everything that influences the generated file
        val cacheKey =
          (org +: artName +: bomVersion +: includeDeps.toString +: outFile.getAbsolutePath +:
            uniqueDeps.map { case (g, a, v) => s"$g:$a:$v" }
          ).mkString("\n")
        val cacheFile = s.cacheDirectory / "makeBom.cachekey"
        val previousKey = if (cacheFile.exists()) Some(IO.read(cacheFile)) else None

        if (previousKey.contains(cacheKey) && outFile.exists()) {
          log.debug(s"[$artName] Artifact BOM up to date at ${outFile.getAbsolutePath}")
        } else {
          val newContent = bomPom(org, artName, bomVersion, uniqueDeps, includeDeps)
          IO.createDirectory(outputFolder)

          // Skip the write (preserving mtime) when an existing file already has identical content,
          // e.g. after the cache was cleared but the BOM itself is still up to date
          val existingContent = if (outFile.exists()) Some(IO.read(outFile)) else None
          if (existingContent.contains(newContent)) {
            log.debug(s"[$artName] Artifact BOM content unchanged at ${outFile.getAbsolutePath}")
          } else {
            IO.write(outFile, newContent)
            log.info(s"[$artName] Created artifact BOM at ${outFile.getAbsolutePath}")
          }
          IO.write(cacheFile, cacheKey)
        }
      }
    }.value,

    makeBomCompileTrigger := Def.taskDyn {
      if (makeBomOnCompile.value) Def.task { makeBom.value }
      else Def.task { () }
    }.triggeredBy(Compile / compile).value
  )
}
