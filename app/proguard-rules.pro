# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# ZXing (QR generation + scanning)
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil
-dontwarn coil.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
