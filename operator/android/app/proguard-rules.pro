# SHADOW Operator ProGuard/R8 rules

# Ktor / SLF4J
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.**
-dontwarn io.ktor.**

# Keep Ktor websocket classes
-keep class io.ktor.websocket.** { *; }
-keep class io.ktor.client.plugins.websocket.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep data classes
-keep class com.shadow.operator.data.** { *; }
-keep class com.shadow.operator.model.** { *; }
