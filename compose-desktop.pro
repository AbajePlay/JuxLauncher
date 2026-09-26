-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** { @kotlinx.serialization.Serializable <fields>; }

-dontwarn kotlinx.coroutines.**
-dontwarn androidx.compose.**
