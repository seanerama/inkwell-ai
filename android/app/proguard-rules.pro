# Keep kotlinx.serialization generated serializers (contract net + agent-output types).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.inkwell.**$$serializer { *; }
-keepclassmembers class com.inkwell.** {
    *** Companion;
}
-keepclasseswithmembers class com.inkwell.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
