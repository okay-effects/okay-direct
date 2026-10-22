plugins { `java-library` }

dependencies {
    api(project(":std"))
    api(platform("org.junit:junit-bom:5.13.4"))
    api("org.junit.jupiter:junit-jupiter")
}
