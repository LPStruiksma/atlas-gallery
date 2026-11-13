plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A permanent *development* signing identity is optional in CI. Do not put
// any password or keystore in Git history; CI injects these values at build time.
val atlasSigningPath = providers.environmentVariable("ATLAS_STABLE_SIGNING_PATH").orNull
val atlasSigningStorePassword = providers.environmentVariable("ATLAS_STABLE_SIGNING_STORE_PASSWORD").orNull
val atlasSigningKeyAlias = providers.environmentVariable("ATLAS_STABLE_SIGNING_KEY_ALIAS").orNull
val atlasSigningKeyPassword = providers.environmentVariable("ATLAS_STABLE_SIGNING_KEY_PASSWORD").orNull
val atlasSigningParts = listOf(
    atlasSigningPath, atlasSigningStorePassword, atlasSigningKeyAlias, atlasSigningKeyPassword
)
val atlasHasStableSigning = atlasSigningParts.all { !it.isNullOrEmpty() }
if (!atlasHasStableSigning && atlasSigningParts.any { !it.isNullOrEmpty() }) {
    throw GradleException("Partial ATLAS signing configuration. Supply all four stable development signing variables or none.")
}

android {
    namespace = "nl.atlas.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.atlas.gallery"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0-upgrade-candidate"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ONNX Runtime ships native code for four ABIs. Keep the S24+ (arm64)
        // and the x86_64 API35 test emulator; drop 32-bit ABIs (~90 MB debug).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    if (atlasHasStableSigning) {
        signingConfigs.create("atlasStableDevelopment") {
            storeFile = file(requireNotNull(atlasSigningPath))
            storePassword = requireNotNull(atlasSigningStorePassword)
            keyAlias = requireNotNull(atlasSigningKeyAlias)
            keyPassword = requireNotNull(atlasSigningKeyPassword)
        }
    }
    buildTypes {
        getByName("debug") {
            if (atlasHasStableSigning) {
                signingConfig = signingConfigs.getByName("atlasStableDevelopment")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-video:3.5.0")
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
    // Native People (#402 wave 2): on-device face detection/embedding runtime (MIT)
    // and resumable, constraint-aware background scanning. No network use.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Experimental Apache-2.0 zoom/subsampling spike: Android tests ONLY.
    // The shipped application does not depend on Telephoto or enable it.
    androidTestImplementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
