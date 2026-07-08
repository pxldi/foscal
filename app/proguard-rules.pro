# Hilt
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep class app.calendarium.** { *; }

# CalendarContract usage does not require extra rules.

# osmdroid (map picker/viewer) resolves tile sources and overlays partly via reflection; keep it
# and silence warnings about its optional dependencies so R8 doesn't strip the map in release.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
