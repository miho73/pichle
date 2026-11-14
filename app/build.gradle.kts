plugins {
    alias(libs.plugins.android.application)
}

    android {
    namespace = "ooo.ionya.driveaway"
        compileSdk {
            version = release(37)
        }

        defaultConfig {
      applicationId = "ooo.ionya.driveaway"
    minSdk = 28
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"

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
      implementation("ai.pleos.playground:Vehicle:2.3.1")

      testImplementation(libs.junit)
      androidTestImplementation(libs.espresso.core)
      androidTestImplementation(libs.ext.junit)
  }