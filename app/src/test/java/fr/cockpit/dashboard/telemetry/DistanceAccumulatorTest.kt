package fr.cockpit.dashboard.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceAccumulatorTest {
    private fun fix(time: Long, speed: Float? = 10f, latitude: Double = 48.0, accuracy: Float = 3f) =
        GpsFix(latitude, 2.0, accuracy, time, speed, if (speed != null) 0.2f else null)

    @Test fun `stationary position jitter never accumulates distance`() {
        val accumulator = DistanceAccumulator()
        accumulator.accept(fix(1_000, 0.1f), 1_000)
        var sum = 0.0
        for (i in 2..30) {
            val sample = accumulator.accept(fix(i * 1_000L, 0.2f, 48.0 + if (i % 2 == 0) 0.00003 else 0.0), i * 1_000L)!!
            sum += sample.distanceMeters
            assertEquals(0f, sample.speedMetersPerSecond)
        }
        assertEquals(0.0, sum, 0.001)
    }

    @Test fun `moving samples integrate reliable GPS speed`() {
        val accumulator = DistanceAccumulator()
        accumulator.accept(fix(1_000, 10f), 1_000)
        val sample = accumulator.accept(fix(3_000, 20f, 48.00027), 3_000)!!
        assertEquals(30.0, sample.distanceMeters, 0.001)
        assertEquals(20f, sample.speedMetersPerSecond)
    }

    @Test fun `stale inaccurate and out of order fixes are ignored`() {
        val accumulator = DistanceAccumulator()
        assertNull(accumulator.accept(fix(1_000), 10_000))
        assertNull(accumulator.accept(fix(10_000, accuracy = 50f), 10_000))
        assertNotNull(accumulator.accept(fix(10_000), 10_000))
        assertNull(accumulator.accept(fix(9_000), 10_000))
        assertNull(accumulator.accept(fix(10_000), 10_000))
        assertNull(accumulator.accept(fix(11_000), 10_000))
    }

    @Test fun `impossible jumps neither count nor replace anchor`() {
        val accumulator = DistanceAccumulator()
        accumulator.accept(fix(1_000), 1_000)
        assertNull(accumulator.accept(fix(2_000, latitude = 49.0), 2_000))
        assertEquals(20.0, accumulator.accept(fix(3_000, latitude = 48.00018), 3_000)!!.distanceMeters, 0.001)
    }

    @Test fun `gaps and explicit pause create a fresh anchor`() {
        val accumulator = DistanceAccumulator()
        accumulator.accept(fix(1_000), 1_000)
        assertEquals(0.0, accumulator.accept(fix(20_000, latitude = 49.0), 20_000)!!.distanceMeters, 0.001)
        accumulator.reset()
        assertEquals(0.0, accumulator.accept(fix(21_000, latitude = 50.0), 21_000)!!.distanceMeters, 0.001)
    }

    @Test fun `without sensor speed only displacement beyond uncertainty counts`() {
        val accumulator = DistanceAccumulator()
        accumulator.accept(fix(1_000, null), 1_000)
        assertEquals(0.0, accumulator.accept(fix(2_000, null, 48.00001), 2_000)!!.distanceMeters, 0.001)
        val moving = accumulator.accept(fix(3_000, null, 48.00011), 3_000)!!
        assertEquals(11.12, moving.distanceMeters, 0.1)
    }

    @Test fun `invalid numbers cannot poison the counters`() {
        val accumulator = DistanceAccumulator()
        assertNull(accumulator.accept(fix(1_000, latitude = Double.NaN), 1_000))
        assertNull(accumulator.accept(fix(1_000, accuracy = Float.NaN), 1_000))
        assertNotNull(accumulator.accept(fix(1_000, speed = Float.NaN), 1_000))
    }
}
