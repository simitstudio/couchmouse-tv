import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from ../signing.properties (never committed):
//   storeFile=C:/path/upload.jks  storePassword=...  keyAlias=...  keyPassword=...
val signing = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.couchmouse.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.couchmouse.tv"
        minSdk = 24 // dispatchGesture needs Android 7.0
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3") // QR code on the TV screen
}
