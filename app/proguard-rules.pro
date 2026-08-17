# Hilt
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep class app.foscal.** { *; }

# CalendarContract usage does not require extra rules.

# Room's own consumer rule is a bare `-keep class * extends androidx.room.RoomDatabase`, which under
# R8 full mode — the default since AGP 8 — keeps the class but *not* its members. WorkManager builds
# its WorkDatabase_Impl reflectively through the no-arg constructor, so with the constructor shrunk
# away every release build died on the first WorkManager call in Application.onCreate:
#
#   java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
#
# Debug builds do not minify, so this is invisible until an APK from the release job is installed.
# Room 2.7 ships the member spec itself; keep it here until WorkManager pulls in a version that has
# it. Written for any RoomDatabase rather than WorkDatabase_Impl alone so it still holds if we ever
# add a database of our own.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# osmdroid (map picker/viewer) resolves tile sources and overlays partly via reflection; keep it
# and silence warnings about its optional dependencies so R8 doesn't strip the map in release.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
