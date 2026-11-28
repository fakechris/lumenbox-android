import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val releaseKeystorePath = System.getenv("LUMENBOX_RELEASE_STORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }
val releaseStorePassword = System.getenv("LUMENBOX_RELEASE_STORE_PASSWORD")?.trim()
val releaseKeyAlias = System.getenv("LUMENBOX_RELEASE_KEY_ALIAS")?.trim()
val releaseKeyPassword = System.getenv("LUMENBOX_RELEASE_KEY_PASSWORD")?.trim()

android {
    namespace = "com.lumenopen.lumenbox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumenopen.lumenbox"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null &&
            !releaseStorePassword.isNullOrEmpty() &&
            !releaseKeyAlias.isNullOrEmpty() &&
            !releaseKeyPassword.isNullOrEmpty()
        ) {
            create("releaseExternal") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("releaseExternal")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The crypto lives in `:core`, a plain JVM module that cannot carry an Android test source set,
    // so its on-device checks run here against the same code and the same server vectors (INV-2290).
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("core/src/test/resources"))
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
