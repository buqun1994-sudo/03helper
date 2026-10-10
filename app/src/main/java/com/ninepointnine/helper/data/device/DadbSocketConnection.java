package com.ninepointnine.helper.data.device;

import dadb.AdbConnection;
import dadb.AdbStream;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * JVM socket binding for the pinned Dadb 1.2.9 protocol engine.
 * Isolates its Kotlin-internal/JVM-public connection ABI in one adapter;
 * no reflection and no duplicated ADB packet/authentication implementation.
 */
public final class DadbSocketConnection {
    // Linux TCP ABI: public Os accepts options but OsConstants omits these names.
    private static final int TCP_KEEPIDLE = 4;
    private static final int TCP_KEEPINTVL = 5;
    private static final int TCP_KEEPCNT = 6;
    private final Socket socket;
    private final AdbConnection connection;
    private DadbSocketConnection(Socket socket, AdbConnection connection) {
        this.socket = socket;
        this.connection = connection;
    }
    public static DadbSocketConnection connect(String host, int port, int connectTimeout, int handshakeTimeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            // Public Android APIs; kernel probes do not poll accessibility/ADB state.
            // Some firmwares do not expose these options, so standard keepalive remains.
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.fromSocket(socket)) {
                Os.setsockoptInt(descriptor.getFileDescriptor(), OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 30);
                Os.setsockoptInt(descriptor.getFileDescriptor(), OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 10);
                Os.setsockoptInt(descriptor.getFileDescriptor(), OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3);
            } catch (Exception ignored) { }
            socket.setSoTimeout(handshakeTimeout);
            AdbConnection connection = AdbConnection.Companion.connect(socket, null);
            socket.setSoTimeout(0);
            return new DadbSocketConnection(socket, connection);
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }
    public AdbStream open(String destination) throws IOException { return connection.open(destination); }
    public boolean supportsFeature(String feature) { return connection.supportsFeature(feature); }
    public void abort() { try { socket.close(); } catch (IOException ignored) { } }
    public void close() {
        abort();
        try { connection.close(); } catch (RuntimeException ignored) { }
    }
}
