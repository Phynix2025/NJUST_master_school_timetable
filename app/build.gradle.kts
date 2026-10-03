plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.edu.njust.kezaizhangxin"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "cn.edu.njust.kezaizhangxin"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.2.0"
    }

    signingConfigs {
        create("release") {
            initWith(getByName("debug"))
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
