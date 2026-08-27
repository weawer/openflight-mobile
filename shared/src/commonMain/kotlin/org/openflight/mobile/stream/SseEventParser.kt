package org.openflight.mobile.stream

data class SseEvent(
    val name: String?,
    val data: String,
)

class SseEventParser {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun append(lineWithOptionalCarriageReturn: String): SseEvent? {
        val line = lineWithOptionalCarriageReturn.removeSuffix("\r")
        if (line.isEmpty()) {
            return dispatch()
        }
        if (line.startsWith(':')) {
            return null
        }

        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(' ')) {
            value = value.drop(1)
        }

        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
        }
        return null
    }

    fun reset() {
        eventName = null
        dataLines.clear()
    }

    private fun dispatch(): SseEvent? {
        if (dataLines.isEmpty()) {
            reset()
            return null
        }

        return SseEvent(eventName, dataLines.joinToString("\n")).also { reset() }
    }
}

class SseByteStreamParser {
    private val lineBytes = mutableListOf<Byte>()
    private val eventParser = SseEventParser()

    fun append(byte: Byte): SseEvent? {
        if (byte != NEWLINE) {
            lineBytes += byte
            return null
        }

        val line = lineBytes.toByteArray().decodeToString()
        lineBytes.clear()
        return eventParser.append(line)
    }

    fun reset() {
        lineBytes.clear()
        eventParser.reset()
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
    }
}
