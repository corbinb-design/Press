import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

// Release key: keystore.properties in the project root (git-ignored). See README "Release signing".
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// Gemini (context layer): key and model IDs from local.properties (git-ignored), in every build type. No key builds
// fine and the AI features fall back to templates. Never publish an APK built with a key (see README).
val localProps: Properties = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun localProp(name: String, default: String) = localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

// versionCode = number of commits on HEAD, so every build from a later commit installs as an update. 1 without git.
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "app.forge"

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "app.forge.lift"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "0.1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProp("GEMINI_API_KEY", "")}\"")
        // Confirm both against AI Studio's "Get code" snippet; override in local.properties without touching code.
        buildConfigField("String", "GEMINI_MODEL", "\"${localProp("GEMINI_MODEL", "gemini-3.8-flash")}\"")
        buildConfigField("String", "GEMINI_MODEL_FALLBACK", "\"${localProp("GEMINI_MODEL_FALLBACK", "gemini-3.5-flash-lite")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("release") else {
                logger.warn("w: keystore.properties not found: the release APK is signed with the DEBUG key.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    testImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // F: QR codes for sharing templates: drawn here, and read from a photo or screenshot (no camera permission).
    implementation("com.google.zxing:core:3.5.3")
    // Context layer: read-only, last 14 days of outside activity. 1.1.0 stable (and its RCs) need compileSdk 36 and
    // AGP 8.9.1+; beta01 is the newest that builds with compileSdk 35 / AGP 8.7.3. Move to 1.1.0 with that upgrade.
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Headless screenshot tests (Robolectric + Roborazzi): ./gradlew recordRoborazziDebug
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
