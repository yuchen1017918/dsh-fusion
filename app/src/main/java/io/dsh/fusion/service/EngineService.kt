package io.dsh.fusion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.dsh.fusion.engine.Channels
import io.dsh.fusion.engine.EngineState
import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.buildForegroundNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 引擎的前台服务。
 *
 * 为什么必须是前台服务：Android 会把后台应用的进程随时回收，而 DSH 的会话、
 * 后台任务、看门狗都活在进程里 —— 一收就全断。
 *
 * 注意 Android 12+ 的规矩：**只允许在界面前台时启动前台服务**。
 * 所以这里先判断 uiVisible，不满足就直接放弃启动并留日志，
 * 而不是去撞 ForegroundServiceStartNotAllowedException。
 */
class EngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var lastState: EngineState? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    FusionRuntime.supervisor().stop()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> scope.launch { FusionRuntime.supervisor().restart() }
            else -> scope.launch {
                val status = FusionRuntime.supervisor().start()
                LogBus.i(TAG, "引擎启动结果：${status.state} ${status.detail}")
            }
        }
        observe()
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildForegroundNotification(
            this,
            Channels.ENGINE,
            "DSH 引擎运行中",
            "本地运行，127.0.0.1 回环，数据不出设备",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 状态变了就广播事件，桌宠与流体云据此变色。 */
    private fun observe() {
        observeJob?.cancel()
        observeJob = scope.launch {
            while (isActive) {
                val status = FusionRuntime.supervisor().status.value
                if (status.state != lastState) {
                    if (lastState == EngineState.RUNNING && status.state != EngineState.RUNNING) {
                        EventBus.emit(FusionEvent.EngineLost(status.detail))
                    }
                    if (status.state == EngineState.RUNNING) {
                        status.url?.let { EventBus.emit(FusionEvent.EngineReady(it)) }
                    }
                    lastState = status.state
                }
                delay(700)
            }
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "引擎服务"
        private const val NOTIFICATION_ID = 7301

        const val ACTION_START = "io.dsh.fusion.engine.START"
        const val ACTION_STOP = "io.dsh.fusion.engine.STOP"
        const val ACTION_RESTART = "io.dsh.fusion.engine.RESTART"

        /**
         * 安全启动。返回 false 表示当前不允许起前台服务（界面前台才行），
         * 调用方应当提示用户点一下界面。
         */
        fun start(context: Context, action: String = ACTION_START): Boolean {
            val app = context.applicationContext as? io.dsh.fusion.FusionApplication
            if (app != null && !app.uiVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LogBus.w(TAG, "界面不在前台，按 Android 12+ 的规则不能启动前台服务")
                return false
            }
            val intent = Intent(context, EngineService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
