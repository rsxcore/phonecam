package com.phonecam

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.io.BufferedInputStream

class MjpegServerTest {
    private fun port() = ServerSocket(0).use { it.localPort }
    private fun connect(port: Int, path: String) = Socket("127.0.0.1", port).apply {
        soTimeout = 2000
        getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
    }
    private fun line(input: BufferedInputStream): String {
        val result = StringBuilder()
        while (true) { val b = input.read(); if (b == -1 || b == 10) return result.toString().trimEnd('\r'); result.append(b.toChar()) }
    }
    @Test fun requiresCodeAndClosesPortOnStop() {
        val port = port(); val server = MjpegServer(port, "123456"); server.start()
        try {
            connect(port, "/stream").use { assertTrue(line(it.getInputStream().buffered()).contains("403")) }
            connect(port, "/?code=123456").use { assertTrue(line(it.getInputStream().buffered()).contains("200")) }
        } finally { server.stop() }
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        val restarted = MjpegServer(port, "654321")
        restarted.start()
        try { connect(port, "/?code=654321").use { assertTrue(line(it.getInputStream().buffered()).contains("200")) } }
        finally { restarted.stop() }
    }
    @Test fun latestFrameAndRotationAreDeliveredTogether() {
        val port = port(); val server = MjpegServer(port, "123456"); server.start()
        try {
            server.submit(byteArrayOf(1,2,3), 90)
            server.submit(byteArrayOf(4,5,6,7), 270)
            connect(port, "/stream?code=123456").use { socket ->
                val input = socket.getInputStream().buffered()
                assertTrue(line(input).contains("200"))
                while (line(input).isNotEmpty()) { }
                assertEquals("--phonecamframe", line(input))
                val headers = mutableListOf<String>()
                while (true) { val l = line(input); if (l.isEmpty()) break; headers.add(l) }
                assertTrue(headers.contains("Content-Length: 4")); assertTrue(headers.contains("X-PhoneCam-Rotation: 270"))
                assertArrayEquals(byteArrayOf(4,5,6,7), input.readNBytes(4))
                server.stop()
                // stop must close even connections currently blocked waiting for a frame.
                while (input.read() != -1) { }
            }
        } finally { server.stop() }
    }
    @Test fun discoveryNeverDisclosesPairingCode() {
        val server = MjpegServer(port(), "987654"); server.start()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val query = "PHONECAM_DISCOVER_V1".toByteArray()
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), 5888))
                val reply = DatagramPacket(ByteArray(128), 128); socket.receive(reply)
                val value = String(reply.data, 0, reply.length)
                assertTrue(value.startsWith("PHONECAM_V1:")); assertFalse(value.contains("987654"))
            }
        } finally { server.stop() }
    }
}

