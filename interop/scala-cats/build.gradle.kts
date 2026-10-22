plugins { scala }

dependencies {
    api(project(":scala"))
    api("org.typelevel:cats-effect_3:3.7.1")
    api("co.fs2:fs2-core_3:3.14.0")
    testImplementation(project(":loom"))
    testImplementation(project(":threads"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-Werror", "-deprecation", "-feature", "-unchecked")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}
