import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.plugins.signing.SigningExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

val ailuxGroupId = providers.gradleProperty("AILUX_GROUP_ID").orElse("io.github.kynnchuan")
val ailuxVersion = providers.gradleProperty("AILUX_VERSION").orElse("0.1.0")
val publishedModules = setOf(
    // Single-dependency umbrella artifact (the only coordinate v0.1 consumers need).
    "ailux",
    // Underlying modules -- published so power users can pin a subset in v1.x.
    "ailux-core",
    "ailux-api",
    "ailux-android",
    "ailux-provider-backend",
    "ailux-provider-mock",
)

subprojects {
    if (name in publishedModules) {
        pluginManager.apply("com.vanniktech.maven.publish")

        // Local repo at build/repo. Task: `publishMavenPublicationToProjectLocalRepository`
        pluginManager.withPlugin("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "ProjectLocal"
                        url = uri("${rootProject.layout.buildDirectory.get().asFile}/repo")
                    }
                }
            }
        }

        pluginManager.withPlugin("com.android.library") {
            val projectName = project.name
            extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = false)
                // Signing is configured manually below (not via signAllPublications())
                // to handle literal `\n` in gradle.properties key values.
                configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))

                coordinates(
                    groupId = ailuxGroupId.get(),
                    artifactId = if (projectName == "ailux") "ailux-sdk" else projectName,
                    version = ailuxVersion.get(),
                )

                pom {
                    name.set(if (projectName == "ailux") "ailux-sdk" else projectName)
                    description.set("Ailux Android LLM SDK module: ${if (projectName == "ailux") "ailux-sdk" else projectName}")
                    url.set("https://github.com/kynnchuan/ailux")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("kynnchuan")
                            name.set("Ailux Contributors")
                            url.set("https://github.com/kynnchuan")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/kynnchuan/ailux.git")
                        developerConnection.set("scm:git:ssh://github.com/kynnchuan/ailux.git")
                        url.set("https://github.com/kynnchuan/ailux")
                    }
                }
            }
        }

        // Manual signing configuration: converts literal `\n` in gradle.properties
        // to real newlines before passing to useInMemoryPgpKeys().
        // Activated only with `-Psigning.enabled=true`.
        if (project.findProperty("signing.enabled")?.toString()?.toBoolean() == true) {
            pluginManager.apply("signing")
            pluginManager.withPlugin("signing") {
                extensions.configure<SigningExtension> {
                    val signingKeyId = project.findProperty("signing.keyId")?.toString()
                    val signingKey = project.findProperty("signing.key")?.toString()
                        ?.replace("\\n", "\n")
                    val signingPassword = project.findProperty("signing.password")?.toString()
                    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                    sign(project.extensions.getByType<PublishingExtension>().publications)
                }
            }
        }
    }
}

/**
 * Maven Central Portal publishing configuration.
 *
 * Uses the vanniktech/gradle-maven-publish-plugin which natively supports
 * the new Maven Central Portal (central.sonatype.com), replacing the
 * deprecated Nexus OSSRH staging workflow.
 *
 * Required properties in `~/.gradle/gradle.properties` (never committed):
 *   mavenCentralUsername         -- Central Portal user token name
 *   mavenCentralPassword         -- Central Portal user token value
 *   signing.keyId                -- short (8-char) or long (16-char) PGP key id
 *   signing.key                  -- ASCII-armored private PGP key, single line
 *                                   with literal `\n` between original lines
 *   signing.password             -- passphrase for the key
 *
 * Typical release flow:
 *   ./gradlew publishAllPublicationsToMavenCentralRepository
 *
 * Local verification (outputs to build/repo, no signing):
 *   ./gradlew publishMavenPublicationToProjectLocalRepository
 *
 * Alternatively publish to ~/.m2 (standard mavenLocal):
 *   ./gradlew publishToMavenLocal
 *
 * Release to Maven Central with signing:
 *   ./gradlew publishAllPublicationsToMavenCentralRepository -Psigning.enabled=true
 *
 * Note: Signing is opt-in via `-Psigning.enabled=true`. Without this flag,
 * local publishing works without any PGP configuration.
 */
