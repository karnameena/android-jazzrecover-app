plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

val releaseSigningEnvironment = mapOf(
    "JAZZ_RELEASE_STORE_FILE" to providers.environmentVariable("JAZZ_RELEASE_STORE_FILE").orNull,
    "JAZZ_RELEASE_STORE_PASSWORD" to providers.environmentVariable("JAZZ_RELEASE_STORE_PASSWORD").orNull,
    "JAZZ_RELEASE_KEY_ALIAS" to providers.environmentVariable("JAZZ_RELEASE_KEY_ALIAS").orNull,
    "JAZZ_RELEASE_KEY_PASSWORD" to providers.environmentVariable("JAZZ_RELEASE_KEY_PASSWORD").orNull
)
val configuredReleaseSigningValues = releaseSigningEnvironment.values.count { !it.isNullOrBlank() }
require(configuredReleaseSigningValues == 0 || configuredReleaseSigningValues == releaseSigningEnvironment.size) {
    "Set all four JAZZ_RELEASE_* environment variables to sign a release, or leave all four unset for an unsigned release."
}
val releaseSigningConfigured = configuredReleaseSigningValues == releaseSigningEnvironment.size

android {
    buildFeatures { buildConfig = true }
    namespace = "com.gunakarna.jazzrecovery"
    compileSdk = 36

    defaultConfig {
        val fbApiKey = providers.gradleProperty("jazzFirebaseApiKey").orElse("").get()
        val fbAppId = providers.gradleProperty("jazzFirebaseAppId").orElse("").get()
        val fbProjectId = providers.gradleProperty("jazzFirebaseProjectId").orElse("").get()
        val fbSenderId = providers.gradleProperty("jazzFirebaseSenderId").orElse("").get()
        buildConfigField("String", "FIREBASE_API_KEY", "\"$fbApiKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$fbAppId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$fbProjectId\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"$fbSenderId\"")
        applicationId = "com.gunakarna.jazzrecovery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(requireNotNull(releaseSigningEnvironment["JAZZ_RELEASE_STORE_FILE"]))
                storePassword = requireNotNull(releaseSigningEnvironment["JAZZ_RELEASE_STORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningEnvironment["JAZZ_RELEASE_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningEnvironment["JAZZ_RELEASE_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
