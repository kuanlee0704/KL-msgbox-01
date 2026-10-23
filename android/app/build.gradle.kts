plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.msgbox"
    compileSdk = 34
    defaultConfig {
        applicationId = "tw.msgbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.0"
    }
    // 固定的除錯簽章: 每次打包出來的 APK 簽章相同, 才能直接「覆蓋安裝」並保留資料
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
