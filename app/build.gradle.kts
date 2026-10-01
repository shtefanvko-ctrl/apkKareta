plugins { id("com.android.application") }

android {
    namespace = "kz.kareta.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.kareta.app"
        buildConfigField("String", "WEB_ORIGIN", "\"https://kareta.kz/\"")
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.3"
    }

    buildTypes {
        debug {
            buildConfigField("String", "WEB_ORIGIN", "\"https://s.kareta.kz/\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
