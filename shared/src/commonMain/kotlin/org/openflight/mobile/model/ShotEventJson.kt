package org.openflight.mobile.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object ShotEventJson {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun decode(payload: String): ShotEvent {
        val event = json.decodeFromString<ShotEvent>(payload)
        if (event.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw UnsupportedShotSchemaException(event.schemaVersion)
        }
        return event
    }
}

class UnsupportedShotSchemaException(
    val actualVersion: Int,
) : IllegalArgumentException(
    "Unsupported mobile shot schema version $actualVersion; expected ${ShotEventJson.SUPPORTED_SCHEMA_VERSION}",
)
