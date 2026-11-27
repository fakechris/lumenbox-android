pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumenbox-android"
include(":core")

val sdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.isDirectory }?.absolutePath
if (sdkDir != null && File(sdkDir, "platforms").isDirectory) {
    include(":app")
}
