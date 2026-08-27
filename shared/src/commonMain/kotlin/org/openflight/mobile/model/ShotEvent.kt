package org.openflight.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ShotEvent(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("event_id")
    val eventId: String,
    val timestamp: String,
    val club: String,
    @SerialName("ball_speed_mph")
    val ballSpeedMph: Double,
    @SerialName("club_speed_mph")
    val clubSpeedMph: Double? = null,
    @SerialName("smash_factor")
    val smashFactor: Double? = null,
    @SerialName("estimated_carry_yards")
    val estimatedCarryYards: Double,
    @SerialName("launch_angle_vertical")
    val launchAngleVertical: Double? = null,
    @SerialName("launch_angle_horizontal")
    val launchAngleHorizontal: Double? = null,
    @SerialName("spin_rpm")
    val spinRpm: Double? = null,
    @SerialName("club_path_deg")
    val clubPathDegrees: Double? = null,
    @SerialName("spin_axis_deg")
    val spinAxisDegrees: Double? = null,
) {
    init {
        requireNotNull(Uuid.parseOrNull(eventId)) { "event_id must be a UUID" }
    }

    val displayClub: String
        get() = club
            .split('_')
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
