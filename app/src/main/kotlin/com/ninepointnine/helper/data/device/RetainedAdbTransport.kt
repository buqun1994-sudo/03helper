package com.ninepointnine.helper.data.device

import dadb.AdbStream
import dadb.Dadb
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer

internal interface RemoteInputEventStream : AdbStream {
    fun markSynchronized()
}

/** Bounded handshake, idle-safe events; each ordinary stream owns an inactivity deadline. */
internal class RetainedAdbTransport private constructor(
    private val connection: DadbSocketConnection,
) : Dadb {
    private val closed = AtomicBoolean(false)
    override fun supportsFeature(feature: String): Boolean = connection.supportsFeature(feature)
    override fun open(destination: String): AdbStream = openStream(destination, request = false, subscription = false)
    fun openInputRequest(destination: String): AdbStream = openStream(destination, request = true, subscription = false)
    fun openInputSubscription(destination: String): RemoteInputEventStream =
        openStream(destination, request = false, subscription = true)
    private fun openStream(destination: String, request: Boolean, subscription: Boolean): RemoteInputEventStream {
        if (closed.get()) throw IOException("adb_connection_closed")
        val opening = deadlines.schedule({ abort() }, OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val delegate = try { connection.open(destination) } finally { opening.cancel(false) }
        val awaitingResponse = AtomicBoolean(request || subscription)
        val responseDeadline = if (awaitingResponse.get()) {
            deadlines.schedule({ if (awaitingResponse.compareAndSet(true, false)) abort() }, OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            null
        }
        val guard = if (destination.startsWith("localabstract:")) null else IdleDeadline(::abort)
        return object : RemoteInputEventStream {
            override val source = if (guard == null) delegate.source else object : ForwardingSource(delegate.source) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    guard.touch()
                    val read = super.read(sink, byteCount)
                    if (read > 0) guard.touch()
                    return read
                }
            }.buffer()
            override val sink = if (guard == null) delegate.sink else object : ForwardingSink(delegate.sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    guard.touch()
                    super.write(source, byteCount)
                    guard.touch()
                }
                override fun flush() {
                    guard.touch()
                    super.flush()
                    guard.touch()
                }
            }.buffer()
            override fun markSynchronized() {
                awaitingResponse.set(false)
                responseDeadline?.cancel(false)
            }
            override fun close() {
                awaitingResponse.set(false)
                responseDeadline?.cancel(false)
                guard?.close()
                runCatching { delegate.close() }
            }
        }
    }
    private fun abort() {
        if (closed.compareAndSet(false, true)) connection.abort()
    }
    override fun close() {
        abort()
        connection.close()
    }
    private class IdleDeadline(private val expire: () -> Unit) : AutoCloseable {
        private val finished = AtomicBoolean(false)
        private val lastActivity = AtomicLong(System.nanoTime())
        @Volatile private var pending: ScheduledFuture<*>? = null
        init { schedule(OPERATION_IDLE_TIMEOUT_NANOS) }
        fun touch() { lastActivity.set(System.nanoTime()) }
        private fun schedule(delayNanos: Long) {
            pending = deadlines.schedule({
                if (!finished.get()) {
                    val remaining = OPERATION_IDLE_TIMEOUT_NANOS - (System.nanoTime() - lastActivity.get())
                    if (remaining <= 0L) {
                        if (finished.compareAndSet(false, true)) expire()
                    } else schedule(remaining)
                }
            }, delayNanos.coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            if (finished.get()) pending?.cancel(false)
        }
        override fun close() {
            finished.set(true)
            pending?.cancel(false)
        }
    }
    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L
        private val OPERATION_IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120)
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "03helper-adb-deadlines").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        fun connect(host: String, port: Int, connectTimeoutMillis: Int, handshakeTimeoutMillis: Int): Dadb =
            RetainedAdbTransport(DadbSocketConnection.connect(host, port, connectTimeoutMillis, handshakeTimeoutMillis))
    }
}
