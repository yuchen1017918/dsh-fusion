plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.dsh.fusion.engine"
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
    // 桌面宿主载体用 WebViewCompat.addDocumentStartJavaScript（官方支持的
    // 「文档开始处注入」API，androidx.webkit 1.9+）。
    // 放在 :engine 而不是 :app —— DesktopCarrier 住在这里。
    implementation("androidx.webkit:webkit:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // 用真的 org.json 覆盖 android.jar 里的桩，否则 JSON 断言全在空转
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // rootfs / npm 的 tar 解包需要处理软链、硬链、可执行位，手写 tar 解析不划算
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz 的纯 Java 实现。选 xz 而不是 zstd 的关键理由就在这里：
    // zstd 在 Android 上要带原生库（得赌它有 bionic 产物），xz 是一个纯 Java 依赖，
    // 在任何设备上都能解 —— 而内置内核包必须「一定能解开」，没有第二次机会。
    implementation("org.tukaani:xz:1.10")
}
