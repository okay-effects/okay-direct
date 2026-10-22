plugins { scala }

dependencies {
    api(project(":api"))
    api(project(":std"))
    api("org.scala-lang:scala-library:2.13.18")
    // Sql's front end: with okay-direct-sql on the class path
    compileOnly(project(":sql"))
    testImplementation(project(":sql"))
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation(project(":otel"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.67.0")
    testImplementation(project(":loom"))
    testImplementation(project(":threads"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-Werror", "-deprecation", "-feature", "-unchecked", "-Xlint")
}

// the tests run on LoomEffects too
tasks.withType<Test> {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}

// the front end's programs on Espresso too, multi-shot: a task of its own, the JVM Espresso
val espressoHome = providers.gradleProperty("espressoHome")
    .orElse(System.getProperty("user.home") + "/.local/espresso-java25-25.0.0-macos-aarch64").get()
dependencies { testImplementation(project(":espresso")) }
tasks.test { filter { excludeTestsMatching("*Espresso*") } }
val espressoTest by tasks.registering(Test::class) {
    description = "The front end's programs on Espresso: multi-shot."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*Espresso*") }
    executable = "$espressoHome/bin/java"
    jvmArgs("--experimental-options", "--java.Continuum=true")
    onlyIf { file(executable).exists() }
}
tasks.check { dependsOn(espressoTest) }
