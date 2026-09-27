-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.jux.launcher.**$$serializer { *; }
-keepclassmembers class ru.jux.launcher.** { *** Companion; }
-keepclasseswithmembers class ru.jux.launcher.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclasseswithmembers class ** { @kotlinx.serialization.Serializable <fields>; }

-keep class ru.jux.launcher.MainKt { public static void main(java.lang.String[]); }

-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

-dontwarn kotlinx.coroutines.**
-dontwarn androidx.compose.**
