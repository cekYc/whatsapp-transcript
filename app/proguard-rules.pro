# sherpa-onnx Kotlin API is called through JNI, so its class and member names
# must stay stable in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
