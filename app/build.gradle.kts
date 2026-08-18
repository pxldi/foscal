import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// AGP 9 has built-in Kotlin support, so the kotlin-android plugin must NOT be applied here — it is
// incompatible with the new DSL. The Compose compiler plugin is still applied separately.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.foscal"
    compileSdk = 37
    // Pinned because AGP's default build-tools version trails compileSdk: left unset it tries to
    // install build-tools 36.0.0, which fails on any machine whose SDK is read-only or offline.
    // 37.0.0 is what the README already lists as a requirement.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.foscal"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val keystoreProps = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
        keystoreProps.load(stream)
    }

    fun cfg(envKey: String, propKey: String): String? {
        val fromEnv = System.getenv(envKey)
        if (!fromEnv.isNullOrBlank()) return fromEnv
        val fromProps = keystoreProps.getProperty(propKey)
        if (!fromProps.isNullOrBlank()) return fromProps
        return null
    }

    val releaseStoreFile = cfg("FOSCAL_STORE_FILE", "storeFile")

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = cfg("FOSCAL_STORE_PASSWORD", "storePassword")
                keyAlias = cfg("FOSCAL_KEY_ALIAS", "keyAlias")
                keyPassword = cfg("FOSCAL_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    // Feeds :app the profile recorded by the macrobenchmark journey.
    baselineProfile(project(":benchmark"))

    implementation(project(":core:core-model"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-data"))

    implementation(libs.androidx.core.ktx)
    // Installs the baseline profile on Android 8 to 11, where the platform will not do it itself.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Durable reminder scheduling: WorkManager survives process death and reboots, which the
    // previous in-process observer did not. hilt-work supplies the @HiltWorker factory.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // OpenStreetMap map view for the on-demand location picker (Apache-2.0, FOSS).
    implementation(libs.osmdroid.android)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
