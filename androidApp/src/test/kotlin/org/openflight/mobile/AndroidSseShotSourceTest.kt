package org.openflight.mobile

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.openflight.mobile.stream.SseEvent
import org.openflight.mobile.stream.ShotStreamUpdate

class AndroidSseShotSourceTest {
    @Test
    fun sendsSseHeadersAndEmitsParsedEvent() = runBlocking {
        val connection = FakeConnection(
            statusCode = 200,
            stream = ByteArrayInputStream(
                ": ping\n\nevent: shot\ndata: {\"schema_version\":1}\n\n".encodeToByteArray(),
            ),
        )
        val source = source(connectionFactory = { connection })

        val updates = source.updates().take(3).toList()

        assertEquals("text/event-stream", connection.getRequestProperty("Accept"))
        assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
        assertEquals(ShotStreamUpdate.Connecting, updates[0])
        assertEquals(ShotStreamUpdate.Connected, updates[1])
        assertEquals(
            ShotStreamUpdate.Event(SseEvent("shot", "{\"schema_version\":1}")),
            updates[2],
        )
    }

    @Test
    fun reconnectsAfterServiceUnavailable() = runBlocking {
        var attempts = 0
        val source = source(
            connectionFactory = {
                attempts += 1
                FakeConnection(
                    statusCode = if (attempts == 1) 503 else 200,
                    stream = ByteArrayInputStream(byteArrayOf()),
                )
            },
        )

        val updates = source.updates().take(4).toList()

        assertTrue(attempts >= 2)
        assertEquals(ShotStreamUpdate.Connecting, updates[0])
        assertEquals(
            ShotStreamUpdate.Error("Too many devices are streaming shots"),
            updates[1],
        )
        assertEquals(ShotStreamUpdate.Connecting, updates[2])
        assertEquals(ShotStreamUpdate.Connected, updates[3])
    }

    @Test
    fun idleTimeoutSurfacesAsRetryableError() = runBlocking {
        val source = source(
            connectionFactory = {
                FakeConnection(
                    statusCode = 200,
                    stream = object : InputStream() {
                        override fun read(): Int = throw SocketTimeoutException("Read timed out")
                    },
                )
            },
        )

        val updates = source.updates().take(3).toList()

        assertEquals(ShotStreamUpdate.Connecting, updates[0])
        assertEquals(ShotStreamUpdate.Connected, updates[1])
        assertEquals(ShotStreamUpdate.Error("Read timed out"), updates[2])
    }

    @Test
    fun closeDisconnectsAnActiveStream() = runBlocking {
        val readStarted = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val connection = FakeConnection(
            statusCode = 200,
            stream = object : InputStream() {
                override fun read(): Int {
                    readStarted.countDown()
                    readReleased.await(2, TimeUnit.SECONDS)
                    return -1
                }
            },
            onDisconnect = readReleased::countDown,
        )
        val source = source(connectionFactory = { connection })
        val collection = launch(Dispatchers.Default) {
            source.updates().collect()
        }

        assertTrue(readStarted.await(2, TimeUnit.SECONDS))
        source.close()
        collection.cancelAndJoin()

        assertTrue(connection.disconnected)
    }

    private fun source(
        connectionFactory: (URL) -> HttpURLConnection,
    ) = AndroidSseShotSource(
        endpoint = URL("http://10.0.2.2:8080/api/shots/stream"),
        connectionFactory = connectionFactory,
        reconnectDelayMillis = 0,
        maximumReconnectDelayMillis = 0,
    )

    private class FakeConnection(
        private val statusCode: Int,
        private val stream: InputStream,
        private val onDisconnect: () -> Unit = {},
    ) : HttpURLConnection(URL("http://10.0.2.2:8080/api/shots/stream")) {
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
            onDisconnect()
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = statusCode

        override fun getInputStream(): InputStream = stream
    }
}
