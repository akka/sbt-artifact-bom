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
    val makeBomIncludeDependencies = settingKey[Boolean]("If true, the generated pom also populates a top-level <dependencies> section (in addition to <dependencyManagement>). For backwards compatibility with consumers that expected the old dependencies-only output (defaults to false)")
    val makeBomIncludeInternalDependencies = settingKey[Boolean]("If true, internal/sibling modules (other projects in the same sbt build) that this project depends on are included in the BOM. Disabled by default because their versions change on every release, which would churn the committed on-disk BOM file; bomPublishSettings enables it so a published BOM pins internal modules at the release version")

    // Settings for a dedicated BOM module: the BOM becomes the module's main published pom, with no
    // jar/sources/docs. Apply via `.settings(bomPublishSettings)` in addition to enabling the plugin.
    //
    // A BOM must be published as the primary pom artifact of its own module: Maven forbids combining
    // a <classifier> with <scope>import</scope>, so a BOM cannot be attached as a classified artifact
    // to a jar-producing module. Give the BOM its own module (e.g. `acme-dependencies`) instead.
    lazy val bomPublishSettings: Seq[Setting[_]] = Seq(
      Compile / packageBin / publishArtifact := false,
      Compile / packageDoc / publishArtifact := false,
      Compile / packageSrc / publishArtifact := false,
      publishMavenStyle := true,
      // A published BOM should pin the internal modules it depends on (e.g. a sibling SPI module),
      // so consumers importing it get those modules too. Version churn is irrelevant here since the
      // pom is published per release rather than committed.
      makeBomIncludeInternalDependencies := true,
      makePom := {
        val content = renderBom(
          update.value,
          projectID.all(ScopeFilter(inAnyProject)).value,
          projectID.value,
          organization.value,
          version.value,
          scalaVersion.value,
          scalaBinaryVersion.value,
          makeBomIncludeDependencies.value,
          makeBomIncludeInternalDependencies.value)
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
                        includeDependencies: Boolean, includeInternal: Boolean): String = {
    val siblings = if (includeInternal) Set.empty[(String, String)] else siblingKeys(allIds, scalaFullVersion, scalaBinVersion)
    val deps = bomDependencies(report, siblings)
    val artId = crossed(projectId, scalaFullVersion, scalaBinVersion)
    bomPom(org, artId, version, deps, includeDependencies)
  }

  // Static defaults live at Global scope so users can override them at ThisBuild or project scope;
  // a project-scoped default would shadow any ThisBuild override through sbt's scope delegation.
  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    makeBomTargetName := "artifact-bom",
    makeBomProjectVersion := "100.0.0",
    makeBomOnCompile := true,
    makeBomIncludeDependencies := false,
    makeBomIncludeInternalDependencies := false
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    makeBomTargetDir := (ThisBuild / baseDirectory).value,
    makeBomScalaVersion := crossScalaVersions.value.headOption,

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
      val includeInternal = makeBomIncludeInternalDependencies.value
      val allProjectIDs = projectID.all(ScopeFilter(inAnyProject)).value
      val targetDir = makeBomTargetDir.value
      val targetName = makeBomTargetName.value
      if (pinnedScala.exists(_ != currentScala)) {
        log.debug(s"[$artName] Skipping artifact BOM generation, scalaVersion $currentScala does not match pinned ${pinnedScala.get}")
      } else {
        val siblings = if (includeInternal) Set.empty[(String, String)] else siblingKeys(allProjectIDs, currentScala, sbv)

        val outputFolder = targetDir / targetName / artName
        val outFile = outputFolder / "pom.xml"

        val uniqueDeps = bomDependencies(report, siblings)

        // Cache key covers everything that influences the generated file
        val cacheKey =
          (org +: artName +: bomVersion +: includeDeps.toString +: includeInternal.toString +: outFile.getAbsolutePath +:
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
