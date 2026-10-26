# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# CleanYourFire: dadb (LocalAdb) and okio reference JVM-only classes they never use on Android.
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.slf4j.**
