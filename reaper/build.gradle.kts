plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tjshea.neonreaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjshea.neonreaper"
        minSdk = 29          // Android 10 (Go edition)
        targetSdk = 36       // Android 16
        versionCode = 4
        versionName = "2.0.0"
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

android.testOptions.unitTests.isIncludeAndroidResources = true
android.testOptions.unitTests.all {
    it.maxHeapSize = "1536m"
    it.forkEvery = 1      // each test class in a fresh JVM: screenshot rendering is memory-hungry
    it.environment("EGL_PLATFORM", "surfaceless")   // headless Mesa for the OpenGL render tests
    it.environment("LIBGL_ALWAYS_SOFTWARE", "1")
    // Robolectric runtime jars are pre-fetched (see HANDOFF.md) because the sandbox can't reach Maven Central directly.
    val deps = File(System.getProperty("user.home"), "robolectric-deps")
    if (deps.isDirectory) {
        it.systemProperty("robolectric.offline", "true")
        it.systemProperty("robolectric.dependency.dir", deps.absolutePath)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // OpenGL ES in JVM tests (renders the 3D engine to PNG on the build machine's software GPU)
    val lwjgl = "3.3.4"
    testImplementation("org.lwjgl:lwjgl:$lwjgl")
    testImplementation("org.lwjgl:lwjgl-egl:$lwjgl")
    testImplementation("org.lwjgl:lwjgl-opengles:$lwjgl")
    testRuntimeOnly("org.lwjgl:lwjgl:$lwjgl:natives-linux")
    testRuntimeOnly("org.lwjgl:lwjgl-opengles:$lwjgl:natives-linux")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
