plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eve.pet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eve.pet"
        minSdk = 26          // A8.0+：TYPE_APPLICATION_OVERLAY 可用
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-m1"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

// M1 原则：零第三方依赖，纯 Android SDK，验证的是系统层能力
dependencies {
}
