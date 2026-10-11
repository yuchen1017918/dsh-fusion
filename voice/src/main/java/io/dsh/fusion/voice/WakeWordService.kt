package io.dsh.fusion.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.dsh.fusion.engine.Channels
import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.Tool
import io.dsh.fusion.engine.ToolBus
import io.dsh.fusion.engine.ToolChannel
import io.dsh.fusion.engine.ToolParam
import io.dsh.fusion.engine.ToolSpec
import io.dsh.fusion.engine.buildForegroundNotification

/**
 * 常驻的离线唤醒服务。
 *
 * 关键取舍：唤醒模型只在本地跑，麦克风数据不落盘、不出设备；
 * 唤醒之后**不自动开始录音上传**，只是把一条事件丢进总线，
 * 由用户（或用户配置的规则）决定要不要真的跟模型说话。
 */
class WakeWordService : Service() {

    private lateinit var paths: FusionPaths
    private lateinit var settings: FusionSettings
    private var engine: WakeWordEngine? = null

    override fun onCreate() {
        super.onCreate()
        paths = FusionPaths(this)
        settings = FusionSettings(this)
        registerVoiceTools()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> stopListening()
            ACTION_RESTART -> {
                stopListening()
                startListening()
            }
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildForegroundNotification(
            this,
            Channels.VOICE,
            "离线唤醒待命",
            "喊「${settings.wakePhrase}」就能叫起 DSH（识别全在本地）",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startListening() {
        if (engine?.listening == true) return
        val modelDir = VoiceModels.modelDir(paths, VoiceModels.CHINESE_SMALL)
        if (!VoiceModels.installed(paths, VoiceModels.CHINESE_SMALL)) {
            LogBus.w(TAG, "语音模型尚未下载，唤醒服务待命但不监听")
            return
        }
        val vosk = VoskWakeWordEngine { modelDir }
        val ok = vosk.start(settings.wakePhrase) { text ->
            EventBus.emit(FusionEvent.Wake(text))
            LogBus.i(TAG, "唤醒词命中 -> 已广播事件")
        }
        engine = if (ok) vosk else null
        if (!ok) {
            LogBus.e(TAG, "唤醒引擎启动失败（模型损坏？麦克风权限？）")
        }
    }

    private fun stopListening() {
        engine?.stop()
        engine = null
        LogBus.i(TAG, "离线唤醒已停止")
    }

    /** 让模型也能控制语音开关（比如「帮我把唤醒关掉」）。 */
    private fun registerVoiceTools() {
        ToolBus.registerAll(
            listOf(
                Tool(
                    ToolSpec(
                        id = "android_voice_wake_status",
                        title = "语音唤醒状态",
                        description = "查询离线唤醒是否在听、唤醒词是什么、模型是否已下载。",
                        channel = ToolChannel.APP,
                    )
                ) {
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("listening", engine?.listening == true)
                        put("phrase", settings.wakePhrase)
                        put("modelReady", VoiceModels.installed(paths, VoiceModels.CHINESE_SMALL))
                        put("modelSizeMB", VoiceModels.CHINESE_SMALL.approxBytes / 1024 / 1024)
                    }
                },
                Tool(
                    ToolSpec(
                        id = "android_voice_wake_set",
                        title = "设置唤醒词",
                        description = "修改唤醒词（改完需要重启唤醒服务生效）。",
                        channel = ToolChannel.APP,
                        params = listOf(ToolParam("phrase", "string", true, "新的唤醒词")),
                    )
                ) { args ->
                    val phrase = args.optString("phrase").ifBlank { return@Tool org.json.JSONObject().put("ok", false).put("error", "缺少 phrase") }
                    settings.wakePhrase = phrase
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("phrase", phrase)
                        put("note", "重启唤醒服务后生效")
                    }
                },
            )
        )
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "唤醒服务"
        private const val NOTIFICATION_ID = 7302

        const val ACTION_START = "io.dsh.fusion.voice.START"
        const val ACTION_STOP = "io.dsh.fusion.voice.STOP"
        const val ACTION_RESTART = "io.dsh.fusion.voice.RESTART"

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun restart(context: Context) = send(context, ACTION_RESTART)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, WakeWordService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
