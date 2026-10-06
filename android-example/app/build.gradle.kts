plugins {
    id("com.android.application")
}

val exampleBaseUrl = providers.gradleProperty("maprouletteBaseUrl").orElse("https://maproulette.org")
val exampleClientId = providers.gradleProperty("maprouletteOAuthClientId").orElse("")
val allowLoopbackOption = providers.gradleProperty("maprouletteAllowLoopback").orElse("false").map(String::toBoolean)
fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

abstract class GenerateNetworkSecurity : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val allowLoopback: Property<Boolean>

    @TaskAction fun generate() {
        val file = outputDirectory.get().file("xml/network_security_config.xml").asFile
        file.parentFile.mkdirs()
        val exception = if (allowLoopback.get()) """
          <domain-config cleartextTrafficPermitted="true">
            <domain includeSubdomains="false">127.0.0.1</domain>
            <domain includeSubdomains="false">localhost</domain>
          </domain-config>
        """ else ""
        file.writeText("""<network-security-config><base-config cleartextTrafficPermitted="false"/>$exception</network-security-config>""")
    }
}
val generateDebugNetworkSecurity = tasks.register<GenerateNetworkSecurity>("generateDebugNetworkSecurity") {
    outputDirectory.set(layout.buildDirectory.dir("generated/debug-network-security"))
    allowLoopback.set(allowLoopbackOption)
}

android {
    namespace = "org.maproulette.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.maproulette.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "org.maproulette.example"
        buildConfigField("String", "MAPROULETTE_BASE_URL", "\"https://maproulette.org\"")
        buildConfigField("String", "MAPROULETTE_OAUTH_CLIENT_ID", "\"\"")
        buildConfigField("boolean", "MAPROULETTE_ALLOW_LOOPBACK", "false")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "MAPROULETTE_BASE_URL", buildString(exampleBaseUrl.get()))
            buildConfigField("String", "MAPROULETTE_OAUTH_CLIENT_ID", buildString(exampleClientId.get()))
            buildConfigField("boolean", "MAPROULETTE_ALLOW_LOOPBACK", allowLoopbackOption.get().toString())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateDebugNetworkSecurity) { it.outputDirectory }
}

dependencies {
    implementation("net.openid:appauth:0.11.1")
    // Ephemeral Custom Tabs (private sign-in tab, so each sign-in can pick an OSM account).
    implementation("androidx.browser:browser:1.9.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("org.maproulette:maproulette-mobile-sdk:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
