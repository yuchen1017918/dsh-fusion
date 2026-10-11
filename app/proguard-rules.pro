# ═══════════════════════════════════════════════════════════════════
#  DSH Fusion 的 R8 规则
#
#  注意：**当前 release 也关着混淆**（见 app/build.gradle.kts 的 isMinifyEnabled=false）。
#  这不是疏忽，是纪律：本项目尚未在真机上跑通过任何一个功能，
#  在零运行期验证的情况下开混淆，等于把「一个变量」变成「两个变量」。
#
#  规则已经按反射点写全了，等真机验证通过后把 isMinifyEnabled 改成 true 即可生效。
# ═══════════════════════════════════════════════════════════════════

# ── Shizuku：AIDL 生成的 Stub/Proxy 全靠反射与 Binder 名字 ──
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# ── Vosk / JNA：JNI 靠类名与方法签名查找 ──
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# ── org.json：被 AIDL 与工具参数大量使用 ──
-keep class org.json.** { *; }

# ── kotlinx.coroutines ──
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── 引擎监督者用反射取子进程 pid（见 EngineSupervisor.pidOf）──
# 反射的是系统类 java.lang.Process 的方法，本身不会被混淆，
# 但保留我们自己继承 Process 的内部类，避免方法被内联掉
-keep class io.dsh.fusion.engine.runtime.** { *; }

# ── 无障碍服务的实例由系统按类名实例化 ──
-keep class io.dsh.fusion.devicectl.FusionAccessibilityService { *; }
-keep class io.dsh.fusion.devicectl.FusionNotificationListener { *; }

# ── 广播接收器由系统按清单里的类名实例化 ──
-keep class io.dsh.fusion.ConfirmationReceiver { *; }

# ── AndroidX / Material 的常规豁免 ──
-dontwarn androidx.**
-keep class androidx.appcompat.** { *; }

# ── 保留行号，线上崩溃才能定位 ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 第三方库的可选依赖（没引入，别报警告）──
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn javax.**
