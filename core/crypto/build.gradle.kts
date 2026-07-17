plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.core.crypto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // No third-party crypto: the platform Keystore + javax.crypto are exactly what we want here.
}
