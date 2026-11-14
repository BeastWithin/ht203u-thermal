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

    signingConfigs {
        create("release") {
            // set in ~/.gradle/gradle.properties, never committed
            val props = project.findProperty("ht203u.storeFile")?.let { project.properties }
            if (props != null) {
                storeFile = file(project.property("ht203u.storeFile") as String)
                storePassword = project.property("ht203u.storePassword") as String
                keyAlias = project.property("ht203u.keyAlias") as String
                keyPassword = project.property("ht203u.keyPassword") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
