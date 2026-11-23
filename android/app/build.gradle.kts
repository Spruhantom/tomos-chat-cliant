import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Transitional only: the existing UI still references the old transport until
// the Pear identity and conversation screens are migrated. Remove this task
// together with android/native/tomchat-noise at the final Pear-only cutover.
val nativeNoiseManifest = rootProject.file("android/native/tomchat-noise/Cargo.toml")
val nativeNoiseOutput = rootProject.file("android/app/src/main/jniLibs")

tasks.register<org.gradle.api.tasks.Exec>("buildTomChatNoise") {
    group = "build"
    description = "Temporarily build the old transport while the Pear-only UI cutover is in progress."
    inputs.file(nativeNoiseManifest)
    inputs.dir(rootProject.file("android/native/tomchat-noise/src"))
    outputs.dir(nativeNoiseOutput)
    workingDir = rootProject.projectDir
    commandLine(
        "cargo", "ndk",
        "--platform", "26",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", nativeNoiseOutput.absolutePath,
        "build", "--release",
        "--manifest-path", nativeNoiseManifest.absolutePath
    )
}

val pearWorkerScript = rootProject.file("p2p/pear-worker/build-android.sh")
val pearWorkerAssets = rootProject.file("android/app/src/main/assets")
val pearWorkerAddons = rootProject.file("android/app/build/pear-addons/jniLibs")

tasks.register<org.gradle.api.tasks.Exec>("buildTomChatPear") {
    dependsOn("buildTomChatNoise")
    group = "build"
    description = "Bundle the shared Pear worker and link its Android native addons."
    inputs.file(pearWorkerScript)
    inputs.dir(rootProject.file("p2p/pear-worker"))
    outputs.dir(pearWorkerAssets)
    outputs.dir(pearWorkerAddons)
    workingDir = rootProject.projectDir
    commandLine("bash", pearWorkerScript.absolutePath)
}

tasks.named("preBuild").configure {
    dependsOn("buildTomChatPear")
}

android {
    namespace = "uk.co.tomos.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.tomos.chat"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Temporary test-release signing: use Android's standard debug key so a
    // release-variant APK can be installed and tested without private secrets.
    // Replace with a protected production keystore before a public production release.
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // These libraries ship the same versioned OSGi manifest; it is not
    // needed in the Android APK and otherwise breaks Java-resource merging.
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
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

    implementation("com.github.holepunchto.bare-kit-kotlin:bare-kit-kotlin:v0.2.7")

    // WebRTC media is independent of the Pear message transport.
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
