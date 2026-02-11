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

# Sherpa-ONNX TTS - keep all classes for JNI access from native code
-keep class com.k2fsa.sherpa.onnx.** { *; }

# PDFBox optional JPX/JPEG2000 (Gemalto) - not bundled; R8 must not fail on missing class
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter

# MediaPipe LLM Inference - keep classes and suppress warnings for compile-time annotations
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
