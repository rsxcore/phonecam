package com.phonecam.net

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PhoneCam wire protocol v2. Mirrored by `server/src/proto.rs`.
 *
 * Runs inside TLS 1.3 with certificates on both sides (see [Identity]). The
 * PC opens with CLIENT_HELLO; an unknown PC gets PAIRING and waits for the
 * user to approve it on the phone. The phone sends an 8-byte preamble (`PCAM`, u16 version, u16 reserved), then
 * both sides exchange messages of the form `u8 type, u32 length, payload`.
 * All integers are little-endian. Video is Annex-B H.264 / HEVC.
 */
object Protocol {
    const val VERSION = 2
    const val PORT = 8080
    const val DISCOVERY_PORT = 5888
    const val MAX_MESSAGE = 16 * 1024 * 1024

    /** Phone → PC, JSON: device name, model and camera capabilities. */
    const val HELLO: Int = 0x01
    /** Phone → PC, JSON: current settings plus live values (ISO, shutter, fps). */
    const val STATE: Int = 0x02
    /** PC → phone, JSON: `{"set": {...}}` partial settings update. */
    const val CONTROL: Int = 0x03
    /** Phone → PC, JSON: `{"code": "123456"}`, the PC is not trusted yet and must wait for approval. */
    const val PAIRING: Int = 0x04
    /** PC → phone, JSON: `{"name": "DESKTOP-1"}`, sent first on every connection. */
    const val CLIENT_HELLO: Int = 0x05
    /** Phone → PC, JSON: `{"ok": true|false}`, the user's answer to a pairing request. */
    const val PAIR_RESULT: Int = 0x06
    /** Phone → PC: u8 codec, u16 width, u16 height, u16 fps, then codec config bytes. */
    const val CONFIG: Int = 0x10
    /** Phone → PC: u8 flags, i64 pts µs, u16 rotation, then one access unit. */
    const val FRAME: Int = 0x11
    const val PING: Int = 0x20
    const val PONG: Int = 0x21

    const val CODEC_H264 = 1
    const val CODEC_HEVC = 2
    const val FLAG_KEY = 1

    fun preamble(): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put('P'.code.toByte()).put('C'.code.toByte()).put('A'.code.toByte()).put('M'.code.toByte())
        .putShort(VERSION.toShort()).putShort(0).array()

    fun header(type: Int, length: Int): ByteArray = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        .put(type.toByte()).putInt(length).array()

    fun config(codec: Int, width: Int, height: Int, fps: Int, csd: ByteArray): ByteArray =
        ByteBuffer.allocate(7 + csd.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(codec.toByte()).putShort(width.toShort()).putShort(height.toShort()).putShort(fps.toShort())
            .put(csd).array()

    fun frameHeader(key: Boolean, ptsUs: Long, rotation: Int): ByteArray =
        ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            .put((if (key) FLAG_KEY else 0).toByte()).putLong(ptsUs).putShort(rotation.toShort()).array()

    fun write(out: OutputStream, type: Int, vararg parts: ByteArray) {
        out.write(header(type, parts.sumOf { it.size }))
        parts.forEach { out.write(it) }
    }

    class Message(val type: Int, val payload: ByteArray)

    /** Blocks until one whole message is read. */
    fun read(input: InputStream): Message {
        val data = DataInputStream(input)
        val type = input.read()
        if (type < 0) throw EOFException()
        val raw = ByteArray(4); data.readFully(raw)
        val length = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_MESSAGE) throw IOException("Message too large: $length")
        val payload = ByteArray(length); data.readFully(payload)
        return Message(type, payload)
    }
}
