# Keep JNI methods connected to native llama.cpp library
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.flowwrite.ai.llm.LlamaBridge { *; }
-keep class com.flowwrite.ai.database.** { *; }
