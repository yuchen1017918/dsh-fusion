plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.dsh.fusion.voice"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            // LogBus 会调 android.util.Log；纯 JVM 单测里它是桩，
            // 不开这个开关会抛 "not mocked" —— 而我们要测的恰好是经过它的代码路径
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // 用真的 org.json 覆盖 android.jar 里的桩，否则 JSON 断言全在空转
    testImplementation("org.json:json:20240303")
    api(project(":engine"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 离线唤醒/识别：Vosk（Apache-2.0，模型首次使用时按需下载）
    implementation("com.alphacephei:vosk-android:0.3.47") {
        // Vosk 的 POM 拉的是 JNA 的桌面 jar；Android 上必须换成 aar 变体
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
