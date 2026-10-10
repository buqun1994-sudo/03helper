package com.ninepointnine.helper.data.device

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual loopback ADB frames exercise idle I/O and close, rather than mocking Dadb. */
class RetainedAdbTransportTest {
    @Test fun eventWaitOutlivesHandshakeTimeout() {
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val accepting = workers.submit {
            server.accept().use { peer ->
                peer.soTimeout = 3000
                val input = DataInputStream(peer.getInputStream())
                val output = peer.getOutputStream()
                readPacket(input)
                send(output, CNXN, 0x01000000, 4096, "device::features=shell_v2;\u0000")
                val opened = readPacket(input)
                send(output, OKAY, 1, opened.first, "")
                assertTrue(release.await(2, TimeUnit.SECONDS))
                send(output, WRTE, 1, opened.first, "event\n")
                readPacket(input)
            }
        }
        val transport = RetainedAdbTransport.connect("127.0.0.1", server.localPort, 500, 200)
        try {
            val stream = transport.open("localabstract:example.remoteinput")
            val read = workers.submit<String> { stream.source.readUtf8LineStrict() }
            val timer = Executors.newSingleThreadScheduledExecutor()
            try {
                timer.schedule({ release.countDown() }, 400, TimeUnit.MILLISECONDS)
                assertEquals("event", read.get(2, TimeUnit.SECONDS))
            } finally { timer.shutdownNow() }
            accepting.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown(); transport.close(); server.close(); workers.shutdownNow()
        }
    }

    @Test fun closingLeaseUnblocksQuietEventRead() {
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val opened = CountDownLatch(1)
        val accepting = workers.submit {
            server.accept().use { peer ->
                peer.soTimeout = 3000
                val input = DataInputStream(peer.getInputStream())
                val output = peer.getOutputStream()
                readPacket(input)
                send(output, CNXN, 0x01000000, 4096, "device::features=shell_v2;\u0000")
                val request = readPacket(input)
                send(output, OKAY, 1, request.first, "")
                opened.countDown()
                runCatching { input.readByte() }
            }
        }
        val transport = RetainedAdbTransport.connect("127.0.0.1", server.localPort, 500, 500)
        try {
            val stream = transport.open("localabstract:example.remoteinput")
            assertTrue(opened.await(1, TimeUnit.SECONDS))
            val waiting = workers.submit<Boolean> { runCatching { stream.source.readUtf8LineStrict() }.isFailure }
            transport.close()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            accepting.get(2, TimeUnit.SECONDS)
        } finally { transport.close(); server.close(); workers.shutdownNow() }
    }

    @Test fun quietEventReaderDoesNotBlockConcurrentShell() {
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val accepting = workers.submit {
            server.accept().use { peer ->
                peer.soTimeout = 3000
                val input = DataInputStream(peer.getInputStream())
                val output = peer.getOutputStream()
                readPacket(input)
                send(output, CNXN, 0x01000000, 4096, "device::features=shell_v2;\u0000")
                val event = readPacket(input)
                send(output, OKAY, 1, event.first, "")
                val shell = readPacket(input)
                send(output, OKAY, 2, shell.first, "")
                sendBytes(output, WRTE, 2, shell.first, byteArrayOf(1, 2, 0, 0, 0, 111, 107, 3, 1, 0, 0, 0, 0))
                send(output, 0x45534c43, 2, shell.first, "")
                assertTrue(release.await(2, TimeUnit.SECONDS))
                send(output, WRTE, 1, event.first, "event\n")
                while (readPacket(input) != (event.first to 1)) Unit
            }
        }
        val transport = RetainedAdbTransport.connect("127.0.0.1", server.localPort, 500, 500)
        try {
            val stream = transport.open("localabstract:example.remoteinput")
            val waiting = workers.submit<String> { stream.source.readUtf8LineStrict() }
            val command = workers.submit<dadb.AdbShellResponse> { transport.shell("fixed-read-only-probe") }
            assertEquals("ok", command.get(2, TimeUnit.SECONDS).output)
            release.countDown()
            assertEquals("event", waiting.get(2, TimeUnit.SECONDS))
            accepting.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); transport.close(); server.close(); workers.shutdownNow() }
    }

    @Test fun missingFirstSubscriptionStateClosesLeaseWithinDeadline() {
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val accepting = workers.submit {
            server.accept().use { peer ->
                peer.soTimeout = 8000
                val input = DataInputStream(peer.getInputStream())
                val output = peer.getOutputStream()
                readPacket(input)
                send(output, CNXN, 0x01000000, 4096, "device::features=shell_v2;\u0000")
                val opened = readPacket(input)
                send(output, OKAY, 1, opened.first, "")
                assertEquals(-1, input.read())
            }
        }
        val transport = RetainedAdbTransport.connect("127.0.0.1", server.localPort, 500, 500) as RetainedAdbTransport
        try {
            val stream = transport.openInputSubscription("localabstract:example.remoteinput")
            val waiting = workers.submit<Boolean> { runCatching { stream.source.readUtf8LineStrict() }.isFailure }
            assertTrue(waiting.get(7, TimeUnit.SECONDS))
            accepting.get(2, TimeUnit.SECONDS)
        } finally { transport.close(); server.close(); workers.shutdownNow() }
    }

    @Test fun synchronizedSubscriptionHasNoIdleDeadline() {
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val accepting = workers.submit {
            server.accept().use { peer ->
                peer.soTimeout = 8000
                val input = DataInputStream(peer.getInputStream())
                val output = peer.getOutputStream()
                readPacket(input)
                send(output, CNXN, 0x01000000, 4096, "device::features=shell_v2;\u0000")
                val opened = readPacket(input)
                send(output, OKAY, 1, opened.first, "")
                send(output, WRTE, 1, opened.first, "initial\n")
                readPacket(input)
                assertTrue(release.await(7, TimeUnit.SECONDS))
                send(output, WRTE, 1, opened.first, "changed\n")
                readPacket(input)
            }
        }
        val transport = RetainedAdbTransport.connect("127.0.0.1", server.localPort, 500, 500) as RetainedAdbTransport
        try {
            val stream = transport.openInputSubscription("localabstract:example.remoteinput")
            assertEquals("initial", stream.source.readUtf8LineStrict())
            stream.markSynchronized()
            timer.schedule({ release.countDown() }, 5300, TimeUnit.MILLISECONDS)
            val waiting = workers.submit<String> { stream.source.readUtf8LineStrict() }
            assertEquals("changed", waiting.get(7, TimeUnit.SECONDS))
            accepting.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); transport.close(); server.close(); timer.shutdownNow(); workers.shutdownNow() }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, Int> {
        val header = ByteArray(24).also(input::readFully)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int
        val first = buffer.int
        val second = buffer.int
        val length = buffer.int
        require(length in 0..65536)
        input.readFully(ByteArray(length))
        return first to second
    }
    private fun send(output: OutputStream, command: Int, first: Int, second: Int, text: String) =
        sendBytes(output, command, first, second, text.toByteArray())
    private fun sendBytes(output: OutputStream, command: Int, first: Int, second: Int, payload: ByteArray) {
        val checksum = payload.sumOf { it.toInt() and 255 }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(first).putInt(second).putInt(payload.size).putInt(checksum).putInt(command xor -1).array()
        output.write(header); output.write(payload); output.flush()
    }
    companion object {
        const val CNXN = 0x4e584e43
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
    }
}
