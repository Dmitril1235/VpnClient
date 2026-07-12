plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vpnclient.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vpnclient.app"
        minSdk = 24
        targetSdk = 34
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

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Движок sing-box для Android.
    // ИНСТРУКЦИЯ: скачай libbox.aar из https://github.com/SagerNet/sing-box-for-android/releases
    // (Releases → последний релиз → Assets → libbox.aar) и положи файл в папку app/libs/
    // Если файла нет — сборка упадёт с ошибкой "libbox.aar not found", это нормально.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    // Зависимости, которые libbox.aar требует от Kotlin-стороны (Go/gomobile-биндинг):
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
