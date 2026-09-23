package com.phonecam

import com.phonecam.stream.Orientation
import org.junit.Assert.assertEquals
import org.junit.Test

/** Matrices captured from a Nothing Phone (1), with the rotations verified on screen. */
class RotationTest {
    @Test fun backCamera() {
        val st = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(90, Orientation.baseRotation(st))
    }

    @Test fun frontCameraIsMirroredButStillNinety() {
        val st = floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        assertEquals(90, Orientation.baseRotation(st))
    }

    @Test fun plainBufferNeedsNoRotation() {
        val st = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        assertEquals(0, Orientation.baseRotation(st))
    }
}
