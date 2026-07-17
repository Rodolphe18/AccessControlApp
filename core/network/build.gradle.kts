plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.rodolphe.oskeysdemo.core.network"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Dev machine on the LAN — the phone must be on the same Wi-Fi. Cleartext HTTP is allowed
            // for this host by the app's network security config (debug only).
            buildConfigField("String", "BASE_URL", "\"http://192.168.1.104:8080/\"")
        }
        release {
            // Filled in once the VPS is deployed (HTTPS via Caddy).
            buildConfigField("String", "BASE_URL", "\"https://TODO-vps-domain/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api, not implementation: HttpException is part of the contract consumers catch (e.g. 401).
    api(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
