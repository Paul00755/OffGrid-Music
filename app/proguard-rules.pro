# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers and source files for meaningful crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve annotations and type signatures for reflection/serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ==============================================================================
# AndroidX Media3 / ExoPlayer
# ==============================================================================
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.datasource.** { *; }
-dontwarn androidx.media3.**

# Keep MediaSession and playback service callbacks
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class * extends androidx.media3.session.MediaSession$Callback { *; }
-keep class * extends androidx.media3.session.MediaLibraryService { *; }

# ==============================================================================
# AndroidX Room Database
# ==============================================================================
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.migration.Migration { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase$Callback { *; }
-dontwarn androidx.room.paging.**

# Keep Room generated implementations
-keep class * extends androidx.room.RoomOpenHelper$Delegate { *; }
-keep class *_Impl { *; }

# ==============================================================================
# Moshi & JSON Serialization
# ==============================================================================
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keep class *JsonAdapter { *; }
-keep class **$$JsonAdapter { *; }

# Preserve data models used in serialization/Room
-keep class com.example.data.model.** { *; }
-keep class com.example.data.local.entities.** { *; }
-keep class com.example.data.local.** { *; }

# ==============================================================================
# Coil Image Loader
# ==============================================================================
-keep class coil.** { *; }
-dontwarn coil.**

# ==============================================================================
# OkHttp & Retrofit Networking
# ==============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ==============================================================================
# Kotlin Coroutines
# ==============================================================================
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

