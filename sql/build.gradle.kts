plugins { `java-library` }

// SQL as an effect: statements are operations, a transaction is their handler (JDBC, java.sql: no dependency)
dependencies {
    api(project(":std"))
    testImplementation(project(":loom"))
    testImplementation(project(":threads"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.h2database:h2:2.3.232")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val exports = listOf("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
tasks.withType<Test> { jvmArgs(exports + "--add-opens=java.base/java.lang=ALL-UNNAMED") }
