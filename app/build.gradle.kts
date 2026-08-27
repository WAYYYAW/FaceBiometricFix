import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val signingProps = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) load(file.inputStream())
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

    signingConfigs {
        create("release") {
            storeFile = file(signingProps.getProperty("storeFile", "release.jks"))
            storePassword = signingProps.getProperty("storePassword", "")
            keyAlias = signingProps.getProperty("keyAlias", "")
            keyPassword = signingProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
