plugins { `java-library` }

dependencies {
    api(project(":api"))
    testImplementation(project(":tck"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// HotSpot's own one-shot continuations, jdk.internal.vm.Continuation, and the thread's scoped-value bindings
// switched with them: to compile, and wherever it runs
val exports = listOf("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
tasks.withType<JavaCompile> { options.compilerArgs.addAll(exports) }
tasks.withType<Test> { jvmArgs(exports + "--add-opens=java.base/java.lang=ALL-UNNAMED") }
