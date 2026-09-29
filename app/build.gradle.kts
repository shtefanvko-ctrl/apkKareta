plugins { id("com.android.application") }

android {
    namespace = "kz.kareta.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.kareta.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.3"
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


tasks.register("verifyContactPickerContract") {
    group = "verification"
    description = "Verifies that single-contact selection does not require broad contacts access."
    doLast {
        val manifestText = file("src/main/AndroidManifest.xml").readText()
        val activityText = file("src/main/java/kz/kareta/app/MainActivity.java").readText()

        check(!manifestText.contains("android.permission.READ_CONTACTS")) {
            "READ_CONTACTS must not be declared for single-contact selection."
        }
        check(!activityText.contains("Manifest.permission.READ_CONTACTS")) {
            "Native permission aliases must not restore broad contacts access."
        }
        check(activityText.contains("case \"contacts\":") &&
                activityText.contains("return new String[0];")) {
            "The contacts permission alias must remain permissionless."
        }
        check(activityText.contains("new Intent(Intent.ACTION_PICK,") &&
                activityText.contains("ContactsContract.CommonDataKinds.Phone.CONTENT_URI")) {
            "pickContact must use the system ACTION_PICK phone-data picker."
        }
    }
}

tasks.named("check").configure {
    dependsOn("verifyContactPickerContract")
}
