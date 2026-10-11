import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名口令绝不硬编码：优先环境变量，其次本地 keystore.properties（进 .gitignore）
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = System.getenv(env) ?: keystoreProps.getProperty(key)

android {
    namespace = "io.dsh.fusion"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.dsh.fusion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        /**
         * 双 ABI：
         *  · arm64-v8a —— 真机（含华为/荣耀全系）
         *  · x86_64    —— 应用宝电脑版（VirtualBox x86_64 安卓虚拟机）与标准 x86 模拟器
         * 两份运行时都由 tools/build-runtime.py 产出，代码侧无需分支：
         * 引擎永远从 applicationInfo.nativeLibraryDir 取 libnode.so。
         */
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        resourceConfigurations += listOf("zh", "en")
    }

    /**
     * 这一行是整个「装完即用」的地基：
     * Android 10 起应用私有目录里的文件不许 exec，只有安装时由包管理器
     * 解压到 nativeLibraryDir 的 lib*.so 才有执行位。所以必须走 legacy 打包
     * （= manifest 里的 extractNativeLibs=true），把 libnode.so / libproot.so 落到磁盘上。
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*")
        }
    }

    androidResources {
        // 语音模型/rootfs 都是运行时下载，assets 里只有 npm 与证书
        noCompress += listOf("tgz")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    /**
     * 独立 keystore + 独立包名（io.dsh.fusion）是刻意为之：
     * 社区里多个 DSH 安卓版共用包名导致「无法并排安装」，融合版从第一天就避开这个坑。
     */
    signingConfigs {
        create("fusion") {
            val storePath = secret("storeFile", "DSH_FUSION_STORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = secret("storePassword", "DSH_FUSION_STORE_PASSWORD")
                keyAlias = secret("keyAlias", "DSH_FUSION_KEY_ALIAS")
                keyPassword = secret("keyPassword", "DSH_FUSION_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            // 不带后缀：应用宝 / 华为测试时用的就是这一份，包名与 release 一致才好对比
            if (signingConfigs.findByName("fusion")?.storeFile?.exists() == true) {
                signingConfig = signingConfigs.getByName("fusion")
            }
        }
        release {
            if (signingConfigs.findByName("fusion")?.storeFile?.exists() == true) {
                signingConfig = signingConfigs.getByName("fusion")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":devicectl"))
    implementation(project(":voice"))
    implementation(project(":overlay"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

/**
 * 防呆：运行时是生成物（两个 ABI 合计 202 MB），为了仓库体积没有入库。
 * 克隆后如果忘了跑 tools/build-runtime.py，AGP 会安静地打出一个**没有原生库**的 APK，
 * 装到手机上才发现在启动界面就崩 —— 这种失败特别难查，所以在这里当场拦下。
 *
 * 只挂在 assemble/bundle 上：不会影响 IDE 同步与 gradle clean。
 */
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    doFirst {
        val jni = file("src/main/jniLibs")
        val soCount = jni.listFiles()?.sumOf { dir -> dir.listFiles()?.count { it.extension == "so" } ?: 0 } ?: 0
        if (soCount == 0) {
            throw GradleException(
                """
                |缺少内置运行时：app/src/main/jniLibs 下没有任何 .so。
                |
                |运行时是生成物，为控制仓库体积未入库。请先执行：
                |    pip install patchelf
                |    python3 tools/build-runtime.py
                |
                |它会从 Termux 镜像拉取 bionic Node 24 与 PRoot 5.1，产出 13 个 .so/ABI。
                |详见 README「四、从零构建」。
                """.trimMargin()
            )
        }
    }
}
