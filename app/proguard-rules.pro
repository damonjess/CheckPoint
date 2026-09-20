# Add project specific ProGuard rules here.

# Protect Retrofit and OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions

# Protect Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.** { *; }
-keepattributes *Annotation*

# Protect Coil (Image Loading)
-keep class coil3.** { *; }
-dontwarn coil3.**

# Protect Netty & Ktor (Embedded LocalServer)
-dontwarn io.netty.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-keep class io.netty.** { *; }
-keep class io.ktor.** { *; }

# CRITICAL: Protect all network and cache data models from being renamed
-keep class com.yourcompany.facesearch.network.model.** { *; }
-keep class com.yourcompany.facesearch.data.cache.** { *; }
-keep class com.yourcompany.facesearch.data.IdentityProfile { *; }
-keep class com.yourcompany.facesearch.data.PublicProfileLead { *; }

# Protect ML Kit & TensorFlow Lite (LiteRT)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
