plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * DSH Fusion 安装器。
 *
 * 它**故意做得小**：只负责「探测设备 → 选产物 → 断点续传拉取 → 交给系统安装」。
 * 真正的应用（自带 Node/PRoot 运行时 + 官方内核树）在 `full-bundled` 分支，
 * 按需下载 —— 用户不该为了「可能用不上」的运行时先背 100 MB。
 *
 * 所以这里没有任何原生库、没有大资产。装完这个包只有几 MB。
 */
android {
    namespace = "io.dsh.fusion.installer"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.dsh.fusion.installer"
        // API 26 起 Android 才有稳定的「未知来源安装」API 与 FileProvider 行为，
        // 低于它给不出可靠的安装引导
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

/**
 * 把仓库根的 catalog/artifacts.json 同步进 assets。
 *
 * 为什么要这个任务：清单是安装器的**契约**，仓库根那一份是「真源」。
 * 手工复制必然出现「改了根目录那份、忘了 assets 里那份」——
 * 表现是安装器按旧清单去下新版本的包，然后校验失败，
 * 而错误信息只会说「文件损坏」。
 */
val syncCatalog by tasks.registering(Copy::class) {
    from(rootProject.file("catalog/artifacts.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(syncCatalog) }
