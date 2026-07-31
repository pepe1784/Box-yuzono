# Rhino (transitive dependency of NewPipeExtractor) references desktop Java APIs
# that do not exist on Android. They appear to be used only in non-Android code paths.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.lang.invoke.**

# Keep NewPipeExtractor and Rhino entry points so R8 does not strip them.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
