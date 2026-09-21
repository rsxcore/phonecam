package com.phonecam
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
class YuvPlanesTest {
    @Test fun paddedPlanarWithBufferOffsets() {
        val y = ByteBuffer.wrap(byteArrayOf(99,1,2,99,3,4,99)).apply { position(1) }
        val u = ByteBuffer.wrap(byteArrayOf(10)); val v = ByteBuffer.wrap(byteArrayOf(20))
        val out = ByteArray(6)
        YuvPlanes.pack(2,2,listOf(y,u,v),intArrayOf(3,1,1),intArrayOf(1,1,1),out)
        assertArrayEquals(byteArrayOf(1,2,3,4,20,10),out)
        assertEquals(1,y.position())
    }
    @Test fun interleavedChromaAndMissingLastPadding() {
        val y = ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8))
        val u = ByteBuffer.wrap(byteArrayOf(10,20,11)); val v = ByteBuffer.wrap(byteArrayOf(20,10,21))
        val out = ByteArray(12)
        YuvPlanes.pack(4,2,listOf(y,u,v),intArrayOf(4,4,4),intArrayOf(1,2,2),out)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,20,10,21,11),out)
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedPlaneRejected() {
        YuvPlanes.pack(2,2,listOf(ByteBuffer.allocate(3),ByteBuffer.allocate(1),ByteBuffer.allocate(1)),intArrayOf(2,1,1),intArrayOf(1,1,1),ByteArray(6))
    }
}
