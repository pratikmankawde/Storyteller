plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.dramebaz.app"
    compileSdk = 35

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.dramebaz.app"
        // Vulkan 1.1 requires API 30+ for vkGetPhysicalDeviceFeatures2, etc.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // Let CMakeLists.txt auto-detect Vulkan capability (don't force GGML_VULKAN=ON)
                arguments += "-DGGML_VULKAN_TRY=ON"
                // Force Release build type for maximum optimization (not RelWithDebInfo)
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Use debug keystore for release so release APK is installable without a separate keystore.
        // For Play Store distribution, create a release keystore and reference it here.
        getByName("debug") { /* default debug signing */ }
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Prevent compression of large model files (ONNX models are already compressed)
    // This ensures faster loading and prevents potential issues with compressed assets
    // Large files (>1MB) are typically not compressed by Android, but we explicitly prevent it
    @Suppress("DEPRECATION")
    aaptOptions {
        noCompress("onnx", "bin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // Exclude duplicate libc++_shared.so from different native libraries
            pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
            pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
        }
    }
}

dependencies {
    // LiteRT-LM for Gemma 3n model inference
    // See: https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md
    // Available versions: https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")

    // Media playback notification support
    implementation("androidx.media:media:1.7.0")

    // Room 2.7.1 for Kotlin 2.2 compatibility
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // PDF text extraction (same as Dramebaz – extract text for display and Qwen LLM)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Sherpa-ONNX for TTS (handles VITS-VCTK models properly)
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.23")

    // Unit test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.google.truth:truth:1.1.5")

    // Android test dependencies
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("androidx.fragment:fragment-testing:1.6.2")
}
