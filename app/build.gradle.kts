import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "yporoc.czntoolkit.patcher"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "yporoc.czntoolkit.patcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.1beta"
        ndk {
            // zstd-jni 原生库：真机 arm64 + 模拟器 x86_64
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.opencc4j)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // 仅 PC 单测注入压缩器用（真机走 cznfast，不含此依赖）
    testImplementation(libs.zstd.jni)
}
