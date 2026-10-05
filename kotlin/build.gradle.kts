plugins {
    kotlin("jvm") version "2.2.10"
    application
}
repositories { mavenCentral() }
group = "org.maproulette"
version = "0.1.0-SNAPSHOT"
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
application { mainClass.set("org.maproulette.sdk.ExampleKt") }
tasks.test { systemProperty("fixtures", rootProject.file("../fixtures").absolutePath) }
