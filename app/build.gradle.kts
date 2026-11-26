plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every Gradle release build is unsigned. A fresh CI job signs the reviewed artifact
// with Android SDK tools; Gradle/plugins never receive the private key.
val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}

android {
    namespace = "de.trainingsapp"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "de.trainingsapp"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.environmentVariable("BUILD_VERSION_CODE").orNull?.toIntOrNull() ?: 68
        versionName = providers.gradleProperty("trainingsappVersionName").get()
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRobolectricSdk)
            // Runtime SDK jars are normal verified/locked Gradle inputs.
            // Robolectric must not download executable jars behind that gate.
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

// Needed to provide the verified private APK to Android\'s installer.
dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.18")
    robolectricSdk("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}
