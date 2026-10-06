plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.dokka") version "2.2.0"
    `maven-publish`
}
repositories { mavenCentral() }

// One version for both platforms: the repository's VERSION file. The Swift package checks the
// same file in a test. On JitPack the Maven version is the requested tag or commit (VERSION
// environment variable), so the coordinates match what consumers ask for.
val sdkVersion = rootProject.file("../VERSION").readText().trim()
group = "com.github.mvexel"
version = System.getenv("JITPACK")?.let { System.getenv("VERSION") } ?: sdkVersion

kotlin { jvmToolchain(17) }
java {
    withSourcesJar()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// The SDK version as a compile-time constant, used for the User-Agent.
val generateVersion by tasks.registering {
    val output = layout.buildDirectory.dir("generated/version")
    val value = sdkVersion
    inputs.property("version", value)
    outputs.dir(output)
    doLast {
        val file = output.get().file("org/maproulette/sdk/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package org.maproulette.sdk
            |
            |/** The SDK version, sent in the User-Agent. Generated from the repository's VERSION file. */
            |public object MapRouletteSdk {
            |    public const val VERSION: String = "$value"
            |}
            |""".trimMargin(),
        )
    }
}
sourceSets.main { kotlin.srcDir(generateVersion) }

// The read-only CLI example lives in its own source set, outside the published JAR:
// ./gradlew runExample --args='16441'
val example by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
tasks.register<JavaExec>("runExample") {
    description = "Runs the read-only CLI example against production MapRoulette."
    classpath = example.runtimeClasspath
    mainClass.set("org.maproulette.sdk.example.ExampleKt")
}

tasks.test {
    systemProperty("fixtures", rootProject.file("../fixtures").absolutePath)
    systemProperty("versionFile", rootProject.file("../VERSION").absolutePath)
}

val javadocJar by tasks.registering(Jar::class) {
    description = "API reference (Dokka HTML) packaged as the javadoc JAR."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "maproulette-mobile-sdk"
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("MapRoulette mobile SDK")
                description.set("Kotlin client for the MapRoulette API: reads, skip and choice-task completion.")
                url.set("https://github.com/mvexel/maproulette-mobile-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("mvexel")
                        name.set("Martijn van Exel")
                    }
                }
                scm {
                    url.set("https://github.com/mvexel/maproulette-mobile-sdk")
                    connection.set("scm:git:https://github.com/mvexel/maproulette-mobile-sdk.git")
                }
            }
        }
    }
}
