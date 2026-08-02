# Rhino and NewPipeExtractor reference desktop-only JDK classes that are not
# available on Android. They are not used in the code paths we exercise.
-dontwarn java.beans.**
-dontwarn java.lang.invoke.**
-dontwarn org.mozilla.javascript.tools.**

# Keep javax.script and jdk.dynalink classes that Rhino / NewPipeExtractor may
# look up via the ServiceLoader / ScriptEngineManager at runtime. Simply
# suppressing the warnings lets R8 strip them, which causes
# "No script engine found" or LinkageError crashes when NewPipeExtractor tries
# to deobfuscate YouTube player JS. Rules taken from AniTail's ProGuard config.
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

# Keep NewPipeExtractor entry points so R8 does not strip them.
-keep class org.schabi.newpipe.extractor.** { *; }

# Keep Rhino classes (including the bytecode writer that may be referenced by
# Rhino contexts). Forcing Rhino to interpreter mode avoids ClassFileWriter at
# runtime, but keeping it prevents R8 from removing the class if it is still
# referenced by reflection or by Rhino itself.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.** { *; }
