plugins { `java-library` }

dependencies {
    api(project(":api"))
    testImplementation(project(":tck"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
