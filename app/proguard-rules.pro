# FaceAlbum project specific ProGuard/R8 rules.
# Keep ML Kit, TensorFlow Lite, and Compose classes that may be reflection-sensitive.

# Preserve Source/Line info for release debugging and mapping-based crash analysis.
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,EnclosingMethod,Signature

# ---- TensorFlow Lite ----
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ---- ML Kit Face Detection ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- FaceAlbum model/config data classes (defensive for reflection-based serialization/tooling) ----
-keep class com.facealbum.model.** { *; }
-keep class com.facealbum.config.** { *; }
-keep class com.facealbum.data.db.** { *; }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---- WorkManager ----
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- Jetpack Compose / Kotlin metadata ----
-keep class androidx.compose.** { *; }
-keep class kotlin.Metadata { *; }
