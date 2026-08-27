package org.openflight.mobile.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.openflight.mobile.sample.SampleShot

class ShotFeedStoreTest {
    @Test
    fun publishesShotFromSseEvent() {
        val store = ShotFeedStore()

        store.accept(ShotStreamUpdate.Connected)
        store.accept(
            ShotStreamUpdate.Event(
                SseEvent(name = "shot", data = SampleShot.json),
            ),
        )

        assertEquals(ShotFeedStatus.Connected, store.state.value.status)
        assertEquals(151.4, store.state.value.latestShot?.ballSpeedMph)
        assertEquals(1, store.state.value.history.size)
    }

    @Test
    fun ignoresReplayWithSameEventId() {
        val store = ShotFeedStore()
        val changedReplay = SampleShot.json.replace("151.4", "199.0")

        store.accept(ShotStreamUpdate.Event(SseEvent("shot", SampleShot.json)))
        store.accept(ShotStreamUpdate.Event(SseEvent("shot", changedReplay)))

        assertEquals(151.4, store.state.value.latestShot?.ballSpeedMph)
        assertEquals(1, store.state.value.history.size)
    }

    @Test
    fun ignoresUnknownEventNames() {
        val store = ShotFeedStore()

        store.accept(ShotStreamUpdate.Event(SseEvent("stats", SampleShot.json)))

        assertNull(store.state.value.latestShot)
        assertEquals(emptyList(), store.state.value.history)
    }

    @Test
    fun malformedShotBecomesRetryableError() {
        val store = ShotFeedStore()

        store.accept(ShotStreamUpdate.Event(SseEvent("shot", "not json")))

        assertIs<ShotFeedStatus.Error>(store.state.value.status)
    }
}
