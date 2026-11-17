plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ooo.ionya.driveaway"
    useLibrary("android.car", false)
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ooo.ionya.driveaway"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(libs.ext.junit)
}
