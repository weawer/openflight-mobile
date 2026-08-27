package org.openflight.mobile.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openflight.mobile.model.ShotEvent
import org.openflight.mobile.model.ShotEventJson

sealed interface ShotFeedStatus {
    data object Idle : ShotFeedStatus
    data object PermissionRequired : ShotFeedStatus
    data object Connecting : ShotFeedStatus
    data object Connected : ShotFeedStatus
    data class Error(val message: String) : ShotFeedStatus
}

data class ShotFeedState(
    val status: ShotFeedStatus = ShotFeedStatus.Idle,
    val history: List<ShotEvent> = emptyList(),
) {
    val latestShot: ShotEvent?
        get() = history.firstOrNull()
}

sealed interface ShotStreamUpdate {
    data object PermissionRequired : ShotStreamUpdate
    data object Connecting : ShotStreamUpdate
    data object Connected : ShotStreamUpdate
    data class Event(val event: SseEvent) : ShotStreamUpdate
    data class Error(val message: String) : ShotStreamUpdate
}

class ShotFeedStore {
    private val mutableState = MutableStateFlow(ShotFeedState())
    private val seenEventIds = mutableSetOf<String>()

    val state: StateFlow<ShotFeedState> = mutableState.asStateFlow()

    fun accept(update: ShotStreamUpdate) {
        when (update) {
            ShotStreamUpdate.PermissionRequired -> setStatus(ShotFeedStatus.PermissionRequired)
            ShotStreamUpdate.Connecting -> setStatus(ShotFeedStatus.Connecting)
            ShotStreamUpdate.Connected -> setStatus(ShotFeedStatus.Connected)
            is ShotStreamUpdate.Error -> setStatus(ShotFeedStatus.Error(update.message))
            is ShotStreamUpdate.Event -> receive(update.event)
        }
    }

    private fun receive(event: SseEvent) {
        if (event.name != null && event.name != SHOT_EVENT) {
            return
        }

        try {
            val shot = ShotEventJson.decode(event.data)
            if (!seenEventIds.add(shot.eventId.lowercase())) {
                return
            }
            mutableState.value = mutableState.value.copy(
                status = ShotFeedStatus.Connected,
                history = (listOf(shot) + mutableState.value.history).take(HISTORY_LIMIT),
            )
        } catch (error: Exception) {
            setStatus(ShotFeedStatus.Error(error.message ?: "Invalid shot payload"))
        }
    }

    private fun setStatus(status: ShotFeedStatus) {
        mutableState.value = mutableState.value.copy(status = status)
    }

    private companion object {
        const val SHOT_EVENT = "shot"
        const val HISTORY_LIMIT = 100
    }
}
