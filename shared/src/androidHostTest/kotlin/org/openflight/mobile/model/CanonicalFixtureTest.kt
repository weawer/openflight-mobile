package org.openflight.mobile.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.openflight.mobile.sample.SampleShot

class CanonicalFixtureTest {
    @Test
    fun decodesExistingIosCanonicalFixture() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val fixture = generateSequence(File(workingDirectory)) { directory ->
            directory.parentFile
        }
            .map { directory ->
                directory.resolve("iosApp/OpenFlightTests/Fixtures/shot_v1.json")
            }
            .firstOrNull(File::isFile)

        assertTrue(fixture != null, "Could not locate the repository's canonical shot_v1.json")
        assertEquals(SampleShot.event, ShotEventJson.decode(fixture.readText()))
    }
}
