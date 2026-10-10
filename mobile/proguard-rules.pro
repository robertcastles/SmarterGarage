# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 'see' what proguard is doing
-verbose

# Obfuscation is enabled (default) — shortens class/method names to reduce dex size.
# Firebase Crashlytics automatically de-obfuscates stack traces using the uploaded mapping file.

# To ensure that exception messages are readable and useful, you might want to keep all exception classes
#-keep public class * extends java.lang.Exception


# Android Auto / Car App Library requirements

# This was needed for gson support in release mode. sad.
-if class *
-keepclasseswithmembers class <1> {
    <init>(...);
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep the data classes used for API responses, including inner classes
-keep class com.lusion.sgauto.GarageStatusResponse { *; }
-keep class com.lusion.sgauto.GarageStatusResponse$** { *; }
-keep class com.lusion.sgauto.GarageCommandRequest { *; }
-keep class com.lusion.sgauto.GarageCommandRequest$** { *; }
-keep class com.lusion.sgauto.GarageCommandResponse { *; }
-keep class com.lusion.sgauto.GarageCommandResponse$** { *; }
-keep class com.lusion.sgauto.PingRequest { *; }
-keep class com.lusion.sgauto.PingResponse { *; }
-keep class com.lusion.sgauto.DevicesResponse { *; }
-keep class com.lusion.sgauto.Device { *; }
-keep class com.lusion.sgauto.Component { *; }
-keep class com.lusion.sgauto.Capability { *; }
-keep class com.lusion.sgauto.Category { *; }

# General rule for all @Keep annotated classes
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Retrofit + Kotlin
-keepattributes Signature
-keepattributes *Annotation*
-keep interface com.lusion.sgauto.SmartThingsApi { *; }

-keep,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation class retrofit2.Response
-keep,allowobfuscation class kotlin.coroutines.Continuation

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not kept
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep your API interface (already narrowly covered by the SmartThingsApi rule
# above; this file previously also had a blanket
# `-keep interface com.lusion.sgauto.** { *; }` rule which kept EVERY interface
# in the app package obfuscation-free — removed as redundant/overbroad).

# Retrofit does reflection on generic parameters
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

# Keep Kotlin Metadata
-keepattributes *Annotation*,Signature,InnerClasses
-keep class kotlin.Metadata { *; }

# R8 full mode strips generic signatures from return types if not kept
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>