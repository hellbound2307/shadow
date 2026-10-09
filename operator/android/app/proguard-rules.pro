# SHADOW Operator ProGuard/R8 rules

# === General ===
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.**
-dontwarn io.ktor.**

# === Room ===
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# === Ktor ===
-keep class io.ktor.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.websocket.** { *; }

# === Compose ===
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# === Kotlin Serialization ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# === Gson ===
-keep class com.google.gson.** { *; }
-keep class com.shadow.operator.data.** { *; }
-keep class com.shadow.operator.model.** { *; }

# === Security Crypto ===
-keep class androidx.security.** { *; }
-dontwarn androidx.security.**

# === SQLCipher ===
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# === BouncyCastle ===
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# === Timber ===
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# === Coil ===
-keep class coil.** { *; }
-dontwarn coil.**

# === ZXing ===
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# === Media3 ===
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# === Hilt ===
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
-keep class javax.inject.** { *; }
-dontwarn javax.inject.**

# === App classes ===
-keep class com.shadow.operator.** { *; }
