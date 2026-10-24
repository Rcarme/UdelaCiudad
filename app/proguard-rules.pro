-keep class com.tgmon.app.data.models.** { *; }
-keep class com.tgmon.app.data.database.** { *; }

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep interface * extends retrofit2.http.* { *; }

-keep class * extends androidx.lifecycle.ViewModel { *; }

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

-keep class kotlin.Metadata { *; }

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

-keep class androidx.security.crypto.EncryptedSharedPreferences { *; }

# Tink / ErrorProne annotations (missing from security-crypto)
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
