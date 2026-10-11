package io.dsh.fusion.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 跨域事件总线。
 *
 * 语音命中、引擎就绪、模型回复、工具调用 —— 这些事各有各的生产者，
 * 但消费者是同一批（悬浮球、流体云胶囊、主界面）。用一条流收口，
 * 新增一个域不需要通知任何人。
 */
sealed class FusionEvent {
    /** 离线唤醒命中。 */
    data class Wake(val text: String) : FusionEvent()

    /** 引擎起来了，url 是可以直接塞进 WebView 的那个。 */
    data class EngineReady(val url: String) : FusionEvent()

    /** 引擎掉了。 */
    data class EngineLost(val detail: String) : FusionEvent()

    /** 一轮回复的摘要（给流体云用）。 */
    data class Reply(val text: String, val done: Boolean) : FusionEvent()

    /** 某条消息被模型写入。 */
    data class Message(val role: String, val preview: String) : FusionEvent()

    /** 设备工具被调用。 */
    data class ToolInvoked(val id: String, val ok: Boolean) : FusionEvent()

    /** 需要用户看一眼的错误。 */
    data class Failure(val message: String) : FusionEvent()
}

object EventBus {

    private val _events = MutableSharedFlow<FusionEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<FusionEvent> = _events

    fun emit(event: FusionEvent) {
        _events.tryEmit(event)
    }
}
