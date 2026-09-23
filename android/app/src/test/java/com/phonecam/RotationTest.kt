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

    @Test fun turningThePhoneFollowsCamera2Convention() {
        // Back camera, base 90: upright 90, left side up 180, right side up 0, upside down 270.
        assertEquals(90, Orientation.streamRotation(90, 0, front = false))
        assertEquals(180, Orientation.streamRotation(90, 90, front = false))
        assertEquals(0, Orientation.streamRotation(90, 270, front = false))
        assertEquals(270, Orientation.streamRotation(90, 180, front = false))
        // Front camera turns the other way.
        assertEquals(0, Orientation.streamRotation(90, 90, front = true))
        assertEquals(180, Orientation.streamRotation(90, 270, front = true))
    }
}
