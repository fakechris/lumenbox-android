# LumenBox Android R8 / ProGuard Keep Rules (`docs/110 §5.4`, INV-2259)
# Protects kotlinx.serialization generated serializers, OpenAPI generated wire models,
# AndroidX WorkManager workers, and Android Keystore / BiometricPrompt reflection bridges.

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# kotlinx.serialization (Kotlin 2.1 + kotlinx-serialization 1.8)
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepnames class <2>$<3>

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keep,includedescriptorclasses class <1>$$serializer { *; }

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    synthetic <init>(...);
}

-keep @kotlinx.serialization.Serializable class com.lumenopen.lumenbox.** { *; }
-keep class com.lumenopen.lumenbox.generated.models.** { *; }

# Keep WorkManager CoroutineWorker & Android Messaging Service entry points
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.lumenopen.lumenbox.app.LumenBoxMessagingService { *; }

# ZXing QR decoder warnings suppression for unused Java AWT references on Android
-dontwarn java.awt.**
-dontwarn javax.imageio.**
