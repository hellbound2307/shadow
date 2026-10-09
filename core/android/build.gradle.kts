buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

// Project repositories are managed in settings.gradle.kts via dependencyResolutionManagement.

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
