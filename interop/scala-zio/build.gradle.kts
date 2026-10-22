plugins { scala }

dependencies {
    api(project(":scala"))
    api("dev.zio:zio_3:2.1.27")
    api("dev.zio:zio-streams_3:2.1.27")
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
