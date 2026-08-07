import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 读取 keystore.properties（本地不存在时跳过，CI 通过 secrets 注入）
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}
val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

android {
    namespace = "com.agent.ta"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.agent.ta"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 显式指定要打包的 ABI，避免 AGP 9.x debug 构建只生成宿主机 ABI 导致 ARM 设备无法安装
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // Release 构建要求完整 keystore 配置
            keystoreProperties["storeFile"]?.let { storeFile = file(it as String) }
            keystoreProperties["storePassword"]?.let { storePassword = it as String }
            keystoreProperties["keyAlias"]?.let { keyAlias = it as String }
            keystoreProperties["keyPassword"]?.let { keyPassword = it as String }
        }
    }

    buildTypes {
        release {
            val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            check(!releaseRequested || requiredSigningKeys.all(keystoreProperties::containsKey)) {
                "Release 构建需要完整的 keystore.properties：${requiredSigningKeys.joinToString()}"
            }
            if (requiredSigningKeys.all(keystoreProperties::containsKey)) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log 等在单元测试中返回默认值而非抛异常
            isReturnDefaultValues = true
        }
    }
}

// Room schema 导出目录，供迁移测试使用
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)

    // OkHttp + Retrofit
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // Desugaring（让 java.time 在 API 24 可用）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
