# R8 for PixKin.
#
# This was off for most of the project, and the reason is in the build file:
# TFLite and ML Kit reach for classes by name, and a wrong keep rule fails at
# runtime in a build that compiled cleanly. Turning it on means re-running the
# embedding equality check in TESTING.md -- the corpus fingerprint must not
# move -- because a renamed class that silently falls back to a different code
# path would change the numbers every threshold in the app is fitted against.
#
# Every rule below exists because something is reached by NAME rather than by
# a reference R8 can see.

# ---------- the inference runtime ----------
# LiteRT/TFLite loads its native delegates and ops reflectively, and the
# Interpreter's own classes are referenced from JNI.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# ---------- ML Kit face detection ----------
# ML Kit resolves its implementation through a manifest-declared registrar and
# instantiates it by name; obfuscating it leaves the registrar looking for a
# class that no longer answers to that name.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.android.gms.common.annotation.** { *; }
-dontwarn com.google.mlkit.**

# ---------- our own reflection ----------
# CrashReports.send reaches FirebaseCrashlytics by name so that a build with no
# google-services.json carries no Google code at all. R8 cannot see that call,
# so without this the class is gone in exactly the build that has it.
-keep class com.google.firebase.crashlytics.FirebaseCrashlytics {
    public static ** getInstance();
    public void setCrashlyticsCollectionEnabled(boolean);
    public void recordException(java.lang.Throwable);
}

# ---------- ViewModels ----------
# Constructed through a factory we wrote, so these are referenced -- but the
# SavedStateHandle keys are strings and the class names appear in saved state.
-keep class com.picfilter.spike.*ViewModel { *; }

# ---------- Activities reached by name ----------
# MainActivity is the launcher and DevOps starts the rest with explicit
# ComponentName intents, which R8 does see; the manifest keeps them anyway.
# Listed for the reader rather than for R8.

# ---------- Coil ----------
-dontwarn okio.**
-dontwarn okhttp3.**

# ---------- our own names, kept readable ----------
# CrashReports writes a stack trace into the app's own storage and the user is
# shown what is in it before it goes anywhere. Obfuscated, that report is a
# wall of a.b.c() and worth nothing to the person deciding whether to send it,
# or to anyone reading it afterwards without the mapping file.
#
# -keepnames, not -keep: R8 still removes this code if it is unused, it just
# does not rename what survives. The app's own classes are a rounding error
# against ML Kit and the model, so this costs almost nothing.
-keepnames class com.picfilter.spike.** { *; }
