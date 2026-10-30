plugins {
    id("com.android.application")
}

android {
    namespace = "org.asterisk.meow.poc"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.asterisk.meow.poc"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
