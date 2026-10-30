plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "libclash"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")
}
