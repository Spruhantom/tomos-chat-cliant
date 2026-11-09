plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val nativeNoiseManifest = rootProject.file("android/native/tomchat-noise/Cargo.toml")
val nativeNoiseOutput = rootProject.file("android/app/src/main/jniLibs")

tasks.register<org.gradle.api.tasks.Exec>("buildTomChatNoise") {
    group = "build"
    description = "Build the Rust Noise core for supported Android ABIs."
    inputs.file(nativeNoiseManifest)
    inputs.dir(rootProject.file("android/native/tomchat-noise/src"))
    outputs.dir(nativeNoiseOutput)
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "--platform", "26",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", nativeNoiseOutput.absolutePath,
        "build", "--release",
        "--manifest-path", nativeNoiseManifest.absolutePath
    )
}

tasks.named("preBuild").configure {
    dependsOn("buildTomChatNoise")
}

android {
    namespace = "uk.co.tomos.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.tomos.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.window:window:1.5.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
