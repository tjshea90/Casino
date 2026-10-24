plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tjshea.dragonhoard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjshea.dragonhoard"
        minSdk = 29          // Android 10 (Go edition)
        targetSdk = 36       // Android 16
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Fixed keystore committed in release/ so every session (and CI) produces
        // APKs that install as upgrades over each other.
        create("release") {
            storeFile = rootProject.file("release/dragonhoard.keystore")
            storePassword = "dragonhoard"
            keyAlias = "dragonhoard"
            keyPassword = "dragonhoard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
