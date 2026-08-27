package org.openflight.mobile

import java.io.EOFException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.openflight.mobile.stream.SseByteStreamParser
import org.openflight.mobile.stream.ShotStreamUpdate

class AndroidSseShotSource(
    private val endpoint: URL = URL(DEFAULT_ENDPOINT),
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val reconnectDelayMillis: Long = INITIAL_RECONNECT_DELAY_MILLIS,
    private val maximumReconnectDelayMillis: Long = MAX_RECONNECT_DELAY_MILLIS,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val idleTimeoutMillis: Int = IDLE_TIMEOUT_MILLIS,
) {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    fun updates(): Flow<ShotStreamUpdate> = flow {
        var currentReconnectDelayMillis = reconnectDelayMillis

        while (currentCoroutineContext().isActive) {
            emit(ShotStreamUpdate.Connecting)
            try {
                streamEvents { update ->
                    if (update == ShotStreamUpdate.Connected) {
                        currentReconnectDelayMillis = reconnectDelayMillis
                    }
                    emit(update)
                }
                throw EOFException("OpenFlight closed the shot stream")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(ShotStreamUpdate.Error(error.userFacingMessage()))
                delay(currentReconnectDelayMillis)
                currentReconnectDelayMillis = (currentReconnectDelayMillis * 2)
                    .coerceAtMost(maximumReconnectDelayMillis)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        activeConnection.getAndSet(null)?.disconnect()
    }

    private suspend fun streamEvents(emit: suspend (ShotStreamUpdate) -> Unit) {
        val connection = connectionFactory(endpoint).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            connectTimeout = connectTimeoutMillis
            readTimeout = idleTimeoutMillis
        }
        activeConnection.set(connection)

        try {
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw UnexpectedHttpStatusException(statusCode)
            }

            emit(ShotStreamUpdate.Connected)
            val parser = SseByteStreamParser()
            connection.inputStream.buffered().use { input ->
                while (currentCoroutineContext().isActive) {
                    val value = input.read()
                    if (value == -1) {
                        return
                    }
                    parser.append(value.toByte())?.let { event ->
                        emit(ShotStreamUpdate.Event(event))
                    }
                }
            }
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun Exception.userFacingMessage(): String = when (this) {
        is UnexpectedHttpStatusException -> if (statusCode == 503) {
            "Too many devices are streaming shots"
        } else {
            "OpenFlight returned HTTP $statusCode"
        }
        is SecurityException -> "Local network access was denied"
        else -> message ?: this::class.simpleName ?: "Unknown network error"
    }

    private class UnexpectedHttpStatusException(
        val statusCode: Int,
    ) : Exception()

    private companion object {
        const val DEFAULT_ENDPOINT = "http://10.0.2.2:8080/api/shots/stream"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val IDLE_TIMEOUT_MILLIS = 45_000
        const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
    }
}
