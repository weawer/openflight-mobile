package org.openflight.mobile

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.openflight.mobile.stream.ShotFeedStore
import org.openflight.mobile.stream.ShotStreamUpdate

class OpenFlightViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = AndroidSseShotSource()
    private var feedJob: Job? = null

    val store = ShotFeedStore()

    fun startFeed() {
        if (feedJob != null) {
            return
        }
        feedJob = scope.launch {
            source.updates().collect(store::accept)
        }
    }

    fun stopFeed() {
        source.close()
        feedJob?.cancel()
        feedJob = null
    }

    fun requireNetworkPermission() {
        store.accept(ShotStreamUpdate.PermissionRequired)
    }

    override fun onCleared() {
        stopFeed()
        scope.cancel()
    }
}
