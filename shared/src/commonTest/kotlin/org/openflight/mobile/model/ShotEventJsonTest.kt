package org.openflight.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.openflight.mobile.sample.SampleShot

class ShotEventJsonTest {
    @Test
    fun decodesCanonicalV1Values() {
        val shot = ShotEventJson.decode(SampleShot.json)

        assertEquals(1, shot.schemaVersion)
        assertEquals("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1", shot.eventId)
        assertEquals("driver", shot.club)
        assertEquals("Driver", shot.displayClub)
        assertEquals(151.4, shot.ballSpeedMph)
        assertEquals(103.2, shot.clubSpeedMph)
        assertEquals(264.0, shot.estimatedCarryYards)
    }

    @Test
    fun preservesMissingOptionalValuesAsNull() {
        val shot = ShotEventJson.decode(
            """{
                "schema_version": 1,
                "event_id": "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                "timestamp": "2026-07-29T19:42:10.123456",
                "club": "driver",
                "ball_speed_mph": 151.4,
                "estimated_carry_yards": 264
            }""",
        )

        assertNull(shot.clubSpeedMph)
        assertNull(shot.spinRpm)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val error = assertFailsWith<UnsupportedShotSchemaException> {
            ShotEventJson.decode(SampleShot.json.replace("\"schema_version\": 1", "\"schema_version\": 2"))
        }

        assertEquals(2, error.actualVersion)
    }

    @Test
    fun rejectsMalformedEventId() {
        assertFailsWith<IllegalArgumentException> {
            ShotEventJson.decode(
                SampleShot.json.replace(
                    "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                    "not-a-uuid",
                ),
            )
        }
    }
}
