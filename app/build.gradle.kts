plugins {
    id("com.android.application")
}

android {
    namespace = "com.picfilter.spike"
    // Compile against 37 so the current AndroidX libraries resolve. targetSdk
    // stays at 36 deliberately: raising it opts the app in to a new platform's
    // behaviour changes -- background limits, permission semantics -- and that
    // is a change to verify on a device, not a number to bump with the
    // dependencies.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.picfilter.spike"
        minSdk = 30
        targetSdk = 36
        // Phases 0-3 are done and the app runs unattended on a real library.
        // The package name stays com.picfilter.spike: renaming it costs a
        // reinstall, which orphans the rescue folder and the enrolment.
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    // lifecycleScope: work started by a screen is cancelled when that screen
    // goes away, rather than posting back into a dead Activity.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // State that outlives a rotation without being hand-packed into a Bundle,
    // and a scope that outlives the Activity rather than the other way round.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
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
