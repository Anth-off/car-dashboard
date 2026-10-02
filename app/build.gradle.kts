plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.cockpit.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.cockpit.dashboard"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
