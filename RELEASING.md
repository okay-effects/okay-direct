# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-direct/maven. No account and no token is needed to use it, only a repository.

## Using a release

Gradle:

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-direct/maven")
}

dependencies {
    implementation("io.github.okay-effects:okay-direct-api:0.1.0")
    implementation("io.github.okay-effects:okay-direct-std:0.1.0")
    runtimeOnly("io.github.okay-effects:okay-direct-loom:0.1.0")      // or -threads, or -espresso
    implementation("io.github.okay-effects:okay-direct-sql:0.1.0")     // if wanted: SQL as an effect
    implementation("io.github.okay-effects:okay-direct-otel:0.1.0")    // if wanted: tracing
}
```

sbt:

```scala-sbt
resolvers += "okay-direct" at "https://okay-effects.github.io/okay-direct/maven"

libraryDependencies ++= Seq(
  "io.github.okay-effects" % "okay-direct-scala" % "0.1.0",          // Scala 3; okay-direct-scala2 for 2.13
  "io.github.okay-effects" % "okay-direct-loom"  % "0.1.0"
)
```

The artifacts are plain (`%`, not `%%`): each Scala front end is built for one Scala, `okay-direct-scala` for Scala 3,
`okay-direct-scala2` for 2.13, and the interop the same (`-scala-cats`, `-scala-zio`, `-scala2-cats`, `-scala2-zio`).
The Java libraries are Java 25 bytecode. `okay-direct-loom` needs its flags at run time, `okay-direct-espresso` Espresso
([docs/implementations.md](docs/implementations.md)).

## Making one

1. On `master`, everything green: `./gradlew build`, with Espresso installed (its tests run on it).

2. The version: `version` in `build.gradle.kts` without `-SNAPSHOT`, committed, tagged (`v0.1.0`).

3. The repository written into `build/pages-maven/`:

   ```sh
   rm -rf build/pages-maven
   ./gradlew publishAllPublicationsToPagesRepository
   ```

4. Onto `gh-pages`, under `maven/`, beside the releases already there:

   ```sh
   git worktree add /tmp/okay-direct-pages gh-pages   # the first time: git worktree add --orphan -b gh-pages /tmp/okay-direct-pages
   mkdir -p /tmp/okay-direct-pages/maven
   rsync -a build/pages-maven/ /tmp/okay-direct-pages/maven/
   touch /tmp/okay-direct-pages/.nojekyll
   git -C /tmp/okay-direct-pages add -A
   git -C /tmp/okay-direct-pages commit -m "release 0.1.0"
   git -C /tmp/okay-direct-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages`.

5. The version back to the next `-SNAPSHOT`.

A release is never rewritten: a version published is what every build that resolved it has.
