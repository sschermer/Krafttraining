plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key material is loaded from protected GitHub Actions secrets.
// Never commit the keystore or its password.
val signingStorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val signingPassword = System.getenv("ANDROID_SIGNING_PASSWORD")
val hasReleaseSigning = !signingStorePath.isNullOrBlank() && !signingPassword.isNullOrBlank()

android {
    namespace = "de.trainingsapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "de.trainingsapp"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("BUILD_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = "0.3.0"
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("permanentRelease") {
                storeFile = file(signingStorePath!!)
                storePassword = signingPassword!!
                keyAlias = "trainingsapp"
                keyPassword = signingPassword
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("permanentRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
