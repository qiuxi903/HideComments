import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 可选的 release 签名。
 *
 * 根目录放一个 `keystore.properties`（格式见 keystore.properties.example）就会自动启用；
 * 没有这个文件时 release 依然能构建，只是产物未签名、装不上 —— 调试阶段用 debug 包就够了。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.qiuxi903.hidecomments.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.qiuxi903.hidecomments"
        minSdk = 26
        targetSdk = 34
        // versionCode 用日期号（YYYYMMDD），是 Xposed 生态的通行做法：
        // LSPosed 管理器靠它判断有没有新版本，日期号保证同一天多次发布也能正确递增，
        // 索引仓库的 Release tag 格式也是 <versionCode>-<versionName>。
        versionCode = 20261020
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 不混淆：hook 侧大量依赖反射按类名找方法，混淆会让排查变得极其痛苦
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    // hook 代码要打进 APK（框架运行时才加载），但设置界面不允许在编译期引用它
    runtimeOnly(project(":hook"))

    // 跨进程读写配置的官方通道
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
