# R8 rules for release builds.
#
# Obfuscation is off: this app is sideloaded, not shipped through a store, and readable
# stack traces in logcat are worth more than the few percent obfuscation would save.
# Shrinking and optimisation (the part that makes Compose fast) stay on.
-dontobfuscate

# NewPipeExtractor runs YouTube's player JavaScript through Rhino, which loads classes
# and members reflectively. Keep both whole rather than chase individual members.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# Jsoup / nanojson (used by the extractor) have optional dependencies that aren't on Android.
-keep class com.grack.nanojson.** { *; }
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**

# Tink (inside androidx.security:crypto) references annotation-only libraries.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# OkHttp's optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
