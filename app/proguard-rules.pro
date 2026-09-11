# Aturan ProGuard/R8 — build saat ini tidak meminify (isMinifyEnabled=false),
# namun aturan ini disiapkan bila R8 diaktifkan nanti.

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.openchai.app.**$$serializer { *; }
-keepclassmembers class com.openchai.app.** { *** Companion; }
-keepclasseswithmembers class com.openchai.app.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Markwon
-keep class io.noties.markwon.** { *; }
