# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# java-llama.cpp JNI bindings - keep all classes to prevent stripping
-keep class de.kherud.llama.** { *; }

# LiteRT-LM (Gemma) - keep for JNI/reflection
-keep class com.google.ai.edge.litertlm.** { *; }

# App entry points and classes used by reflection/JNI
-keep class com.dramebaz.app.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# PDFBox optional JPX/JPEG2000 (Gemalto) - not bundled; R8 must not fail on missing class
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
