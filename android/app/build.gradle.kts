plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.beastin.ht203u"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beastin.ht203u"
        minSdk = 21
        targetSdk = 33   // 34 needs RECEIVER_EXPORTED flags the bundled UVC library does not pass
        versionCode = 1
        versionName = "0.1"
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

dependencies {
    // UVC camera library (Apache-2.0, jiangdongguo/AndroidUSBCamera, prebuilt native code included).
    // Isochronous USB streaming is done in its native code; this app only calls its Java API.
    implementation(mapOf("name" to "libuvc-3.2.9", "ext" to "aar"))
}
