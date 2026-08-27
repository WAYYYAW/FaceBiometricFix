plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.way.facebiometricfix"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.way.facebiometricfix"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
