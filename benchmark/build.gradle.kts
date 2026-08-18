import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Not shipped. This module exists only to drive the app on a real device and record which of its
// classes and methods run during a cold start, so the release build can hand ART that list up
// front instead of leaving it to JIT the whole UI on someone's first few sessions.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.foscal.benchmark"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Profile generation needs a rooted or userdebug device, which in practice means a recent
        // emulator image; nothing older than this is worth supporting for a build-time tool.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
