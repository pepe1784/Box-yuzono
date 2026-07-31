# Rhino and NewPipeExtractor reference desktop-only JDK classes that are not
# available on Android. They are not used in the code paths we exercise.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.lang.invoke.**
-dontwarn org.mozilla.javascript.tools.**

# Keep NewPipeExtractor entry points so R8 does not strip them.
-keep class org.schabi.newpipe.extractor.** { *; }

# Keep Rhino classes (including the bytecode writer that may be referenced by
# Rhino contexts). Forcing Rhino to interpreter mode avoids ClassFileWriter at
# runtime, but keeping it prevents R8 from removing the class if it is still
# referenced by reflection or by Rhino itself.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
