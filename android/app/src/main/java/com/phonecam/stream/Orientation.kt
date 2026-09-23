package com.phonecam.stream

object Orientation {
    /**
     * Clockwise rotation that makes the encoded (sensor-oriented) image
     * upright in the phone's natural orientation, read from the transform
     * the camera framework itself gives the preview. More reliable than
     * SENSOR_ORIENTATION: front cameras are additionally mirrored for the
     * preview, and HALs differ in how they combine the two.
     */
    fun baseRotation(st: FloatArray): Int {
        fun display(s: Float, t: Float): Pair<Int, Int> {
            val x = st[0] * s + st[4] * t + st[12]
            val y = st[1] * s + st[5] * t + st[13]
            return Math.round(x) to Math.round(1 - y) // back into encoded-image coordinates
        }
        val rotations = mapOf<Int, (Int, Int) -> Pair<Int, Int>>(
            0 to { s, t -> s to t }, 90 to { s, t -> 1 - t to s },
            180 to { s, t -> 1 - s to 1 - t }, 270 to { s, t -> t to 1 - s },
        )
        val probes = listOf(0 to 0, 1 to 0, 0 to 1)
        for ((angle, r) in rotations) {
            val plain = probes.all { (s, t) -> display(s.toFloat(), t.toFloat()) == r(s, t) }
            val mirrored = probes.all { (s, t) -> display(s.toFloat(), t.toFloat()) == r(s, t).let { (x, y) -> 1 - x to y } }
            if (plain || mirrored) return angle
        }
        return -1
    }
}
