// QuartzLamp App 模块 — 拾音灯 M3E 控制器
// Kotlin DSL | FastBle 2.3.4 源码收编（app/libs-src，协议见 docs/ble_protocol.md）

plugins {
    id("com.android.application")
    // AGP 9.2+ auto-applies kotlin-android — do NOT apply manually
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.deserthouse.quartzlamp"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.deserthouse.quartzlamp"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FastBle 2.3.4 厂商同款源码（保留 com.clj.fastble 原包名，与厂商调用路径等价）
    sourceSets {
        getByName("main").java.srcDir("libs-src")
    }

    // ━━━ Release 签名：凭据仅从 ~/.gradle/gradle.properties 注入（QUARTZLAMP_* 前缀）━━━
    // 缺失/空白时回退 debug 签名，保证 CI 与新克隆可构建。
    val releaseCredentialProps = listOf(
        "QUARTZLAMP_STORE_FILE", "QUARTZLAMP_STORE_PASS",
        "QUARTZLAMP_KEY_ALIAS", "QUARTZLAMP_KEY_PASS"
    ).map { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }
    val hasReleaseCredentials = releaseCredentialProps.all { it != null }

    signingConfigs {
        if (hasReleaseCredentials) {
            create("release") {
                storeFile = file(releaseCredentialProps[0]!!)
                storePassword = releaseCredentialProps[1]
                keyAlias = releaseCredentialProps[2]
                keyPassword = releaseCredentialProps[3]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "FORCE_REAL_BLE", "false")
            signingConfig = if (hasReleaseCredentials) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // debug 专用冒烟开关：-PforceRealBle=true 强制走真实 FastBle 路径（AVD 验证 F01 初始化，
            // 无 BLE 外设时预期扫描空列表而非崩溃）；release 恒 false
            buildConfigField(
                "boolean", "FORCE_REAL_BLE",
                providers.gradleProperty("forceRealBle").orNull ?: "false"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
