plugins {
    id("com.android.application")
}

android {
    namespace = "com.picfilter.spike"
    compileSdk = 36

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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // lifecycleScope: work started by a screen is cancelled when that screen
    // goes away, rather than posting back into a dead Activity.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
}
