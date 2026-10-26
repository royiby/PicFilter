import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Crash reporting is opt-in twice over: the build only has a reporter if a
 * google-services.json is present, and the app only sends anything if the user
 * said yes to a specific report. Neither is a default.
 *
 * Keeping it conditional means CI -- which has no config and no secrets --
 * builds the same source without the Firebase plugins, and a build made from a
 * clean clone has no Google SDK in it.
 */
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.picfilter.spike"
    compileSdk = 37

    defaultConfig {
        // The id users install under. Permanent once anyone has it, which is
        // why it is not com.picfilter.spike: shipping "spike" forever, under a
        // name that is not the product's, is a decision you only get to make
        // once. The Kotlin package stays as it is -- renaming that moves every
        // file for no user-visible gain.
        applicationId = "com.pixkin.app"
        // 33, not 30. Every path in the app gates on READ_MEDIA_IMAGES, and
        // that permission does not exist before Android 13 -- on 11 and 12 the
        // constant is just a string naming nothing, the request can never be
        // granted, and the app installs and then cannot read a single photo.
        // Claiming those devices in the manifest was the bug; lint had been
        // saying so as four InlinedApi warnings.
        minSdk = 33
        targetSdk = 37
        // Phases 0-3 are done and the app runs unattended on a real library.
        // The package name stays com.picfilter.spike: renaming it costs a
        // reinstall, which orphans the rescue folder and the enrolment.
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    /**
     * Release signing, if a keystore has been provided.
     *
     * keystore.properties is gitignored and never committed -- the key is the
     * developer's identity, and a key in a public repository is a key anyone
     * can sign as you with. Without the file the release build is simply
     * unsigned, which fails loudly at install rather than silently shipping a
     * debug-signed APK.
     *
     * This block has to come BEFORE buildTypes: findByName is evaluated when
     * buildTypes is configured, so declaring it after silently hands the
     * release build a null signing config and produces an unsigned APK.
     */
    signingConfigs {
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            create("release") {
                val k = Properties()
                props.inputStream().use { k.load(it) }
                storeFile = rootProject.file(k.getProperty("storeFile"))
                storePassword = k.getProperty("storePassword")
                keyAlias = k.getProperty("keyAlias")
                keyPassword = k.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is off: it needs keep rules for TFLite's and ML Kit's
            // reflection, and getting those wrong fails at runtime in a build
            // that compiled cleanly. Turning it on means re-running the
            // embedding equality check in TESTING.md.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            ndk {
                // 90 MB of this app's 122 MB is four copies of the same native
                // libraries. x86 and x86_64 are emulators; armeabi-v7a is 32-bit
                // hardware that does not ship Android 13, which is our minSdk.
                // One ABI, for the phones that can actually run it.
                abiFilters += "arm64-v8a"
            }
        }
    }

    // One screen is Compose, as a measured comparison rather than a migration.
    // See PersonScreen.kt.
    buildFeatures { compose = true }

    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The JVM tests touch Android types (FaceObs holds a Bitmap) without
        // calling into them; returning defaults keeps the stubs from throwing.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures { buildConfig = true }

    lint {
        // Deliberate exemptions and their reasons live in app/lint.xml.
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose's test rule drags in Espresso 3.5.0, which calls
    // InputManager.getInstance by reflection -- a method that no longer exists
    // on API 37, so every test in a class holding a ComposeTestRule dies in
    // Espresso.onIdle before reaching its own assertions. Pinned forward.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Reached by reflection in CrashReports.send, so its absence is a
    // runtime no-op rather than a compile error.
    if (hasFirebaseConfig) {
        implementation(platform("com.google.firebase:firebase-bom:34.1.0"))
        implementation("com.google.firebase:firebase-crashlytics")
    }

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    // lifecycleScope: work started by a screen is cancelled when that screen
    // goes away, rather than posting back into a dead Activity.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // State that outlives a rotation without being hand-packed into a Bundle,
    // and a scope that outlives the Activity rather than the other way round.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    // LiteRT, which is what TensorFlow Lite became. org.tensorflow:tensorflow-lite
    // stops at 2.17.0 and pins litert 1.0.1, whose .so files are not aligned for
    // the 16 KB memory pages Android 15 introduced -- lint flagged three of
    // them, and on a 16 KB device an unaligned library fails to load at all.
    // Same org.tensorflow.lite.Interpreter API, so no code changed, and the
    // embeddings are bit-identical: re-scoring three photos reproduced their
    // similarities to six decimal places.
    //
    // Held at 1.4.2 rather than 2.3.0 on purpose. 2.x ships Kotlin 2.4.0
    // metadata and this project's toolchain is on 2.2.0, so taking it means
    // upgrading Kotlin -- a change to the build, not a dependency bump, and
    // one that would want its own verification of the scoring pipeline.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.google.android.material:material:1.14.0")
    // Recycling, so a grid costs the tiles on screen rather than the tiles in
    // the queue. The hand-built version made one view per item in a ScrollView.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Bounded memory cache, cancellation on recycle, parallel decode. The
    // hand-rolled loop retained every bitmap it ever decoded.
    implementation("io.coil-kt:coil:2.7.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}
