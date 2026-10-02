import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Debug builds remain local development builds. Distributed builds must use the
// same externally backed-up key; never fall back to a runner's debug keystore.
val cockpitSigningEnvironment = listOf(
    "COCKPIT_SIGNING_STORE_FILE",
    "COCKPIT_SIGNING_STORE_PASSWORD",
    "COCKPIT_SIGNING_KEY_ALIAS",
    "COCKPIT_SIGNING_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val cockpitSigningConfigured = cockpitSigningEnvironment.values.all { !it.isNullOrBlank() }

val checkCockpitReleaseSigning by tasks.registering {
    doLast {
        val missing = cockpitSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) {
            "Release signing requires a persistent keystore. Missing: ${missing.joinToString()}. See docs/SIGNING.md."
        }
        val keystoreFile = file(cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_STORE_FILE")!!)
        check(keystoreFile.isFile) {
            "The persistent release keystore does not exist. See docs/SIGNING.md."
        }
        val keystore = KeyStore.getInstance(
            keystoreFile,
            cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_STORE_PASSWORD")!!.toCharArray(),
        )
        val certificate = checkNotNull(keystore.getCertificate(cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_KEY_ALIAS")!!)) {
            "The configured signing alias has no certificate."
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        val expected = rootProject.file("docs/release-signing-certificate.sha256").readText().trim()
        check(fingerprint == expected) {
            "Release signing differs from the pinned Cockpit GPS identity; in-place updates would fail. See docs/SIGNING.md."
        }
    }
}

android {
    namespace = "fr.cockpit.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.cockpit.gps"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (cockpitSigningConfigured) {
            create("persistentRelease") {
                storeFile = file(cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_STORE_FILE")!!)
                storePassword = cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_STORE_PASSWORD")
                keyAlias = cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_KEY_ALIAS")
                keyPassword = cockpitSigningEnvironment.getValue("COCKPIT_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (cockpitSigningConfigured) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "1g"
            // Optional infrastructure override; ordinary builds use Robolectric's default repository.
            listOf("robolectric.dependency.repo.url", "cockpit.screenshot.dir").forEach { key ->
                System.getProperty(key)?.let { value -> test.systemProperty(key, value) }
            }
        }
    }

}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(checkCockpitReleaseSigning)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14")
}
