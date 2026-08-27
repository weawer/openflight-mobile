package org.openflight.mobile.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseByteStreamParserTest {
    @Test
    fun preservesBlankLineAndDispatchesShotAfterHeartbeat() {
        val parser = SseByteStreamParser()
        val stream = ": ping\n\nevent: shot\ndata: {\"schema_version\":1}\n\n"

        val events = stream.encodeToByteArray().asList().mapNotNull(parser::append)

        assertEquals(
            listOf(SseEvent(name = "shot", data = "{\"schema_version\":1}")),
            events,
        )
    }

    @Test
    fun handlesCrLfMultilineDataAndConsecutiveEvents() {
        val parser = SseByteStreamParser()
        val stream = "data: first\r\ndata: second\r\n\r\ndata: third\r\n\r\n"

        val events = stream.encodeToByteArray().asList().mapNotNull(parser::append)

        assertEquals(
            listOf(
                SseEvent(name = null, data = "first\nsecond"),
                SseEvent(name = null, data = "third"),
            ),
            events,
        )
    }

    @Test
    fun resetDiscardsPartialBytesAndEvent() {
        val parser = SseByteStreamParser()
        "event: shot\ndata: partial".encodeToByteArray().forEach { byte ->
            assertNull(parser.append(byte))
        }

        parser.reset()
        val events = "data: replacement\n\n"
            .encodeToByteArray()
            .asList()
            .mapNotNull(parser::append)

        assertEquals(listOf(SseEvent(name = null, data = "replacement")), events)
    }
}
