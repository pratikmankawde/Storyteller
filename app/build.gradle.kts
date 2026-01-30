plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.dramebaz.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dramebaz.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF"
                )
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    // Prevent compression of large model files (ONNX models are already compressed)
    // This ensures faster loading and prevents potential issues with compressed assets
    // Large files (>1MB) are typically not compressed by Android, but we explicitly prevent it
    @Suppress("DEPRECATION")
    aaptOptions {
        noCompress("onnx", "gguf", "bin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
    }
    
    // NDK configuration for llama.cpp (Qwen LLM integration)
    ndkVersion = "26.1.10909125"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
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
    
    // Media playback notification support
    implementation("androidx.media:media:1.7.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // PDF text extraction (same as Dramebaz – extract text for display and Qwen LLM)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    
    // Sherpa-ONNX for TTS (handles VITS-VCTK models properly, includes its own ONNX Runtime)
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.23")
    
    // Android test dependencies
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
