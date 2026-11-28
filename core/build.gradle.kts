plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.animalsniffer)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.zxing.core)
    implementation(libs.bouncycastle.bcprov)

    signature(variantOf(libs.gummy.bears.api28) { artifactType("signature") })

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    dependsOn(tasks.named("animalsnifferMain"))
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "15s")
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("contracts/v1.openapi.json").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    packageName.set("com.lumenopen.lumenbox.generated")
    globalProperties.set(
        mapOf(
            "models" to "",
            "modelDocs" to "false",
            "modelTests" to "false",
        )
    )
    additionalProperties.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "enumPropertyNaming" to "UPPERCASE",
        )
    )
}

// Conformance checks shared with the instrumented tests in `:app` (`app/src/androidTest`), so the
// same vectors run on OpenJDK here and on Android's runtime there (INV-2291).
kotlin.sourceSets.named("test") {
    kotlin.srcDir("src/conformanceTest/kotlin")
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
}

// `:core` compiles against JDK 17 but runs on Android's java.* from minSdk 28, where JDK APIs
// such as URLDecoder.decode(String, Charset) or BigInteger.TWO do not exist (INV-2290, INV-2293).
// The Gummy Bears signature is the Android API 28 surface; any call outside it fails the build.
animalsniffer {
    sourceSets = listOf(project.sourceSets["main"])
}
