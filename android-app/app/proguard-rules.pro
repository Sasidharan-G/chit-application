# R8 / ProGuard rules for the release build (minifyEnabled true).
# Library-specific rules (Room, Firebase, Compose) ship inside those
# libraries' own consumer rules; only what this app needs on top is listed here.

# Keep line numbers so a crash report from a release build can still be read.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Room reads the entities by column name, so their field names must survive shrinking /
# obfuscation.
-keep class com.jothivel.chits.data.local.entity.** { *; }

# Kotlin coroutines / Firebase Tasks are covered by their consumer rules.
