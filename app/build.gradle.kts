plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cyrusublerman.distaut"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyrusublerman.distaut"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-dev"
    }

    signingConfigs {
        create("sharedDebug") {
            // Public, development-only key. Never use this configuration for release builds.
            storeFile = file("distaut-debug.keystore")
            storePassword = "distaut-debug"
            keyAlias = "distaut-debug"
            keyPassword = "distaut-debug"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":feature:editor"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
