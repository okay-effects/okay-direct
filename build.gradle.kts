// each module is published as io.github.okay-effects:okay-direct-<module>. A release is a Maven repository on GitHub
// Pages (RELEASING.md): `./gradlew publishAllPublicationsToPagesRepository` writes it into build/pages-maven
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "io.github.okay-effects"
    version = "0.1.1-SNAPSHOT"

    repositories { mavenCentral() }

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }
    configure<JavaPluginExtension> { withSourcesJar() }
    configure<BasePluginExtension> { archivesName = "okay-direct-${project.name}" }
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("module") {
                artifactId = "okay-direct-${project.name}"
                from(components["java"])
                pom {
                    name = "okay-direct-${project.name}"
                    description = "okay-direct: algebraic effects in direct style on the JVM — perform a method call, handle a body; one API, implementations on Loom, threads and Espresso (multi-shot); Scala 3 and 2.13 front ends; cats-effect and ZIO"
                    url = "https://github.com/okay-effects/okay-direct"
                    licenses {
                        license {
                            name = "Apache-2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    developers {
                        developer {
                            id = "sergey-scherbina"
                            name = "Sergiy Shcherbyna"
                            email = "sergey.scherbina@gmail.com"
                        }
                    }
                    scm {
                        url = "https://github.com/okay-effects/okay-direct"
                        connection = "scm:git:https://github.com/okay-effects/okay-direct.git"
                    }
                }
            }
        }
        repositories {
            maven {
                name = "pages"
                url = uri(rootProject.layout.buildDirectory.dir("pages-maven"))
            }
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-Xss512k")
    }
}
