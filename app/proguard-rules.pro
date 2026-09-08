# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:/Users/User/AppData/Local/Android/Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html
#
# This file previously didn't exist even though the release build type
# referenced it via proguardFiles(...) — a release build would fail at
# configuration time with "file not found". Firebase/Room/Hilt/WorkManager
# ship their own consumer ProGuard rules inside their AARs and merge those
# in automatically, so this file only needs to cover this app's own code.

# Keep data/entity classes intact — they're read reflectively in a few
# places (Room entities, Firestore field access) and stripping/renaming
# their fields would silently break persistence or serialization.
-keep class com.waterdistrict.meterreader.data.local.entity.** { *; }
-keep class com.waterdistrict.meterreader.data.remote.** { *; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
