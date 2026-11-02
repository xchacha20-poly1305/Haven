# Allow R8 to proceed with missing classes (JSch references JNA, GSSAPI, SLF4J,
# Log4j2, and Unix sockets which are unavailable on Android)
-ignorewarnings

# Defensive: if any future AGP / library bump adds an
# `-assumenosideeffects class android.util.Log { ... }` rule (which
# would strip log calls entirely from release builds), pin our
# critical Log statements here. As of AGP 8.9.1's
# proguard-android-optimize.txt no such rule ships.

# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Keep JSch
-keep class com.jcraft.jsch.** { *; }

# JSch optional dependencies not available on Android
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
# Keep JNA — native JNI accesses Pointer.peer field by name (needed for IronRDP)
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.Log4j2Logger
-dontwarn com.jcraft.jsch.Slf4jLogger
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.JUnixSocketFactory

# Keep termlib classes — native JNI renderer accesses fields by name
-keep class org.connectbot.terminal.** { *; }

# sshlib's Ed25519 JCE provider asks for its OWN package name while installing
# itself:
#
#   Ed25519Provider.class.getPackage().getName()      (Ed25519Provider.setup)
#
# R8 flattens ~26k classes into the root package, and a class with no package
# returns null from getPackage() — so that line throws NullPointerException
# inside the provider's constructor, killing the connection immediately after
# ECDH_REPLY (#513). Keeping the name keeps the package, which is the whole
# point of this rule; the members are incidental.
#
# Only reached via getEd25519FallbackProvider(), i.e. when the platform's own
# Ed25519 is unusable — which is why it depends on the device rather than
# failing for everyone.
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519Provider { *; }

# Keep mosh transport + generated protobuf classes.
# The pure-Kotlin transport reflects on protobuf field names like `width_`.
# If R8 renames those fields, Mosh connects but never establishes a usable
# terminal session in release builds.
-keep class sh.haven.mosh.** { *; }

# Keep smbj (reflection-based protocol handling)
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-dontwarn javax.el.**

# Keep protobuf generated classes — protobuf-lite uses reflection on field names
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
-keep class sh.haven.mosh.proto.** { *; }

# Keep the entire et-kotlin submodule. The previous keep rule covered only
# `sh.haven.et.protocol.**`, leaving `sh.haven.et.transport.EtTransport`,
# `sh.haven.et.crypto.EtCrypto`, and `sh.haven.et.EtLogger` exposed to R8 —
# verified against the v5.5.0 release mapping.txt where EtTransport is
# renamed to h5.b. Eternal Terminal connections fail in release builds as
# a result. Mirror the broad `sh.haven.mosh.**` rule for consistency.
-keep class sh.haven.et.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep JNA Structure subclasses — IronRDP (UniFFI-generated sh.haven.rdp.**)
# uses @Structure.FieldOrder("capacity", "len", ...) string literals that
# JNA resolves reflectively at runtime. R8 renames the @JvmField properties
# to single letters, Structure.deriveLayout() can't find them, and every
# RDP connection fails with "unknown or zero size (ensure all fields are
# public)" — issue #93.
-keep class * extends com.sun.jna.Structure {
    <fields>;
    <init>(...);
}
-keep class sh.haven.rdp.** { *; }

# Keep the SPICE bindings (UniFFI-generated sh.haven.spice.**) for the same
# reason as sh.haven.rdp above: JNA loads the generated UniffiLib interface
# reflectively and casts the proxy to it — R8's rename turned that into a
# messageless ClassCastException 7ms into every release-build SPICE connect,
# before a single byte reached the server (#549).
-keep class sh.haven.spice.** { *; }

# Keep Shizuku API — Haven calls Shizuku only via reflection
# (Class.forName("rikka.shizuku.Shizuku").getMethod("pingBinder") etc).
# Without this, R8 prunes pingBinder / addBinderReceivedListenerSticky /
# checkSelfPermission / requestPermission / newProcess, and the reflection
# lookups throw NoSuchMethodException at runtime — issue #82.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }

# sora-editor's TextMate language registry deserializes JSON language /
# theme / grammar definitions with Gson, into both its own wrapper types
# (rosemoe.sora.langs.textmate.registry) AND the underlying tm4e engine
# types (org.eclipse.tm4e.*). R8 obfuscates the class names which Gson
# then reports as "Abstract classes can't be instantiated" the first
# time the editor opens any file with a detectable language. Keep both
# package trees whole — they're all data carriers.
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keepclassmembers class io.github.rosemoe.sora.langs.textmate.** {
    <init>(...);
    <fields>;
}
-keepclassmembers class org.eclipse.tm4e.** {
    <init>(...);
    <fields>;
}

# Joni (Java Oniguruma regex engine) and jcodings (its byte-encoding
# dep) cannot be minified — Joni has heavy reliance on cross-class
# static constants and reflective lookups, and R8 stripping any of
# them produces NullPointerException in <clinit> the first time a
# regex is compiled. Both libs are pulled in transitively by tm4e.
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
