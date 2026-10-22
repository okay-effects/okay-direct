plugins { `java-library` }

// every operation a span of OpenTelemetry: an observer of the api (Observer.watch)
dependencies {
    api(project(":api"))
    api("io.opentelemetry:opentelemetry-api:1.67.0")
    testImplementation(project(":std"))
    testImplementation(project(":loom"))
    testImplementation(project(":threads"))
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.67.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.67.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val exports = listOf("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
tasks.withType<Test> { jvmArgs(exports + "--add-opens=java.base/java.lang=ALL-UNNAMED") }
