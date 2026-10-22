plugins { `java-library` }

// Espresso, Java on Truffle, JDK 25: its continuations are multi-shot by copying. Where it is installed:
// -PespressoHome=..., by default ~/.local/espresso-java25-25.0.0-macos-aarch64
val espressoHome = providers.gradleProperty("espressoHome")
    .orElse(System.getProperty("user.home") + "/.local/espresso-java25-25.0.0-macos-aarch64").get()

dependencies {
    api(project(":api"))
    compileOnly(files("$espressoHome/languages/java/lib/continuations.jar"))
    testImplementation(project(":tck"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val access = "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"
tasks.withType<JavaCompile> { options.compilerArgs.add(access) }

// the tests run on Espresso itself, its continuations on
tasks.withType<Test> {
    executable = "$espressoHome/bin/java"
    jvmArgs("--experimental-options", "--java.Continuum=true", access, "--add-opens=java.base/java.lang=ALL-UNNAMED")
    onlyIf { file(executable).exists() }
}
