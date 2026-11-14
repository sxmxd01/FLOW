plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.focusflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.focusflow"
        minSdk = 30            // Android 11+
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    // One fixed key for every build, so a new APK installs OVER the old one (no uninstall needed from now on).
    signingConfigs {
        create("focusflow") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("focusflow")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Convenience: lets `assembleRelease` produce an installable APK out of the box.
            // Replace with your own keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("focusflow")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
