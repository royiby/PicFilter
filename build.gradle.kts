plugins {
    id("com.android.application") version "9.4.1" apply false
    // Applied by :app only when an app/google-services.json exists. Without
    // one the build carries no Google code at all, which is the only way the
    // app's own promise that nothing leaves the phone is true by construction
    // rather than by configuration.
    id("com.google.gms.google-services") version "4.4.3" apply false
    id("com.google.firebase.crashlytics") version "3.0.6" apply false
    // Must match the Kotlin the Android plugin brings with it (2.2.10): the
    // Compose compiler is a Kotlin compiler plugin, not a library, so a
    // mismatch is a build failure rather than a runtime surprise.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
