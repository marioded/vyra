import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.signing.SigningExtension

plugins {
    java
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.nmcp)
}

val junitApi = libs.junit.jupiter.api
val junitLauncher = libs.junit.platform.launcher
val junitEngine = libs.junit.jupiter.engine
val spotlessPlugin = libs.plugins.spotless.get().pluginId

val publishableProjects =
    setOf("vyra-core", "vyra-redis", "vyra-inmemory", "vyra-jackson", "vyra-gson")

val vyraModuleNames =
    mapOf(
        "vyra-core" to "Vyra Core",
        "vyra-redis" to "Vyra Redis",
        "vyra-inmemory" to "Vyra In-Memory",
        "vyra-jackson" to "Vyra Jackson",
        "vyra-gson" to "Vyra Gson",
    )

val vyraModuleDescriptions =
    mapOf(
        "vyra-core" to
            "Core message bus, routing, correlation, timeouts and handler dispatch for Vyra.",
        "vyra-redis" to "Redis transport for Vyra, built on Lettuce.",
        "vyra-inmemory" to "Dependency-free in-memory transport for Vyra.",
        "vyra-jackson" to "Jackson-backed serializer for Vyra (JSON, Smile and CBOR).",
        "vyra-gson" to "Gson-backed serializer for Vyra.",
    )

allprojects {
    group = "com.marioded.vyra"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply<JavaPlugin>()
    apply(plugin = spotlessPlugin)

    dependencies {
        testImplementation(junitApi)
        testRuntimeOnly(junitEngine)
        testRuntimeOnly(junitLauncher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.22.0")
            target("src/**/*.java")
            licenseHeader(
                """
                /*
                 * Licensed under the Apache License, Version 2.0 (the "License");
                 * you may not use this file except in compliance with the License.
                 * You may obtain a copy of the License at
                 *
                 *     http://www.apache.org/licenses/LICENSE-2.0
                 *
                 * Unless required by applicable law or agreed to in writing, software
                 * distributed under the License is distributed on an "AS IS" BASIS,
                 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
                 * See the License for the specific language governing permissions and
                 * limitations under the License.
                 */
                """.trimIndent(),
                "^package"
            )
        }
    }

    if (name in publishableProjects) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        apply(plugin = "com.gradleup.nmcp")

        java {
            withSourcesJar()
            withJavadocJar()
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    pom {
                        name.set(vyraModuleNames[project.name])
                        description.set(vyraModuleDescriptions[project.name])
                        url.set("https://github.com/marioded/vyra")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("marioded")
                                name.set("Mario D.")
                            }
                        }
                        scm {
                            url.set("https://github.com/marioded/vyra")
                            connection.set("scm:git:https://github.com/marioded/vyra.git")
                            developerConnection.set("scm:git:git@github.com:marioded/vyra.git")
                        }
                    }
                }
            }
        }

        configure<SigningExtension> {
            val signingKey = providers.gradleProperty("signingKey").orNull
            if (signingKey != null) {
                useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

dependencies {
    nmcpAggregation(project(":vyra-core"))
    nmcpAggregation(project(":vyra-redis"))
    nmcpAggregation(project(":vyra-inmemory"))
    nmcpAggregation(project(":vyra-jackson"))
    nmcpAggregation(project(":vyra-gson"))
}

nmcpAggregation {
    centralPortal {
        username =
            providers
                .gradleProperty("mavenCentralUsername")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                .orNull
                ?: ""
        password =
            providers
                .gradleProperty("mavenCentralPassword")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                .orNull
                ?: ""

        publishingType = "USER_MANAGED"
    }
}