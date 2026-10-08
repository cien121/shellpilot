plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.chan.shellpilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chan.shellpilot"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.3.6"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // 固定 debug 签名：keystore 提交在仓库里，每次构建签名一致，
    // 用户才能覆盖升级安装（否则 runner 每次生成不同的 debug.keystore，
    // Android 会报签名不一致拒绝安装）。
    // 注意：AGP 默认已创建 debug 配置，这里用 getByName 修改，不能 create。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
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
    packaging {
        resources {
            // SSHJ / BouncyCastle bring duplicate META-INF entries
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/**/OSGI-INF/*"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room (server configs + snippets)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // SSHJ - SSH/SFTP transport (Apache 2.0)
    // Exclude its transitive BouncyCastle (we pin our own version below)
    implementation("com.hierynomus:sshj:0.41.1") {
        exclude(group = "org.bouncycastle")
    }

    // Full BouncyCastle - Android's built-in BC lacks X25519 etc.
    // Registered first in ShellPilotApp.onCreate via Security.insertProviderAt
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")

    // connectbot termlib - terminal emulation (Apache 2.0)
    // TODO: re-enable once a termlib version compatible with compileSdk 34 is available
    // (0.2.1/0.3.11 require compileSdk 37). TerminalBridge is stubbed meanwhile.
    // implementation("org.connectbot:termlib:0.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // EncryptedSharedPreferences —记住服务器密码（AES256 加密存储）
    implementation("androidx.security:security-crypto:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
