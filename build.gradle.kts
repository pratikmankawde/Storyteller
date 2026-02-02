plugins {
    id("com.android.application") version "8.8.0" apply false
    // Kotlin 2.2.21 required for LiteRT-LM (litertlm-android) compatibility
    // LiteRT-LM 0.9.0-alpha02 was compiled with Kotlin 2.2.0 metadata format
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // KSP version must match Kotlin version (format: kotlinVersion-kspVersion)
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
}
