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
        versionCode = 1
        versionName = "0.1-spike"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}
