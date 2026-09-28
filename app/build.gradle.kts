plugins { id("com.android.application") }

android {
    namespace = "kz.kareta.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.kareta.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.webkit:webkit:1.17.1")
}
