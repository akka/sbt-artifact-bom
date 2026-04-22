# sbt-artifact-bom

`sbt-artifact-bom` is an sbt plugin designed to generate a Maven Bill of Materials (BOM) in the form of a `pom.xml` file. It captures all direct and indirect dependencies of your project (as resolved by sbt) and flattens them into a single list of dependencies.

## Features

- **Flattened Dependency Tree**: Automatically includes both direct and transitive dependencies.
- **Maven Compatibility**: Generates a standard `pom.xml` file.
- **Configurable**: Allows customization of the output directory, folder name, and BOM project version.
- **Automatic Execution**: Triggered automatically by the `compile` task.

## Installation

Add the following to your `project/plugins.sbt`:

```scala
addSbtPlugin("io.akka.sbt" % "sbt-artifact-bom" % "1.0.0-SNAPSHOT")
```

## Usage

Enable the plugin in your `build.sbt`:

```scala
lazy val myProject = (project in file("."))
  .enablePlugins(ArtifactBomPlugin)
```

The `makeBom` task is automatically triggered when you run `compile`. You can also run it manually:

```bash
sbt makeBom
```

The resulting BOM will be generated at:
`[baseDirectory]/artifact-bom/[projectName]/pom.xml`

## Settings

The plugin provides the following settings:

| Setting | Description | Default Value |
|---------|-------------|---------------|
| `makeBomTargetDir` | The base directory where the BOM directory is stored. | `(ThisBuild / baseDirectory).value` |
| `makeBomTargetName` | The name of the directory where BOM files are stored. | `"artifact-bom"` |
| `makeBomProjectVersion`| The version string used in the generated `pom.xml`. | `"100.0.0"` |

## How it works

The plugin inspects the `update` report for the `compile` and `runtime` configurations. It collects all unique modules (de-duplicating by organization and name) and formats them into a Maven `<dependencies>` block within a `pom.xml`.

## License

This project is licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

---
© 2026 Akka Team
