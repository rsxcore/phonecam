package com.phonecam

import java.nio.ByteBuffer

/** Packs all three ImageProxy planes; plane 0 contains luma only, even in NV21 mode. */
object YuvPlanes {
    fun pack(width: Int, height: Int, buffers: List<ByteBuffer>, rows: IntArray, pixels: IntArray, out: ByteArray) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(buffers.size == 3 && out.size >= width * height * 3 / 2)
        for (plane in 0..2) {
            val b = buffers[plane].duplicate()
            val base = b.position()
            val w = if (plane == 0) width else width / 2
            val h = if (plane == 0) height else height / 2
            require(base.toLong() + (h - 1).toLong() * rows[plane] + (w - 1).toLong() * pixels[plane] < b.limit())
            if (plane == 0 && pixels[plane] == 1) {
                for (y in 0 until h) { b.position(base + y * rows[plane]); b.get(out, y * width, width) }
            } else {
                val offset = if (plane == 0) 0 else width * height + if (plane == 1) 1 else 0
                val step = if (plane == 0) 1 else 2
                for (y in 0 until h) for (x in 0 until w) {
                    out[offset + (y * w + x) * step] = b.get(base + y * rows[plane] + x * pixels[plane])
                }
            }
        }
    }
}
