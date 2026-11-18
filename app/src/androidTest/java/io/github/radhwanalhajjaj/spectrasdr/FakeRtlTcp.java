// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small rtl_tcp server for the emulator tests: the 12-byte greeting, then unsigned 8-bit IQ of
 * one carrier in noise at the pace of a real dongle. It notes the commands it gets and obeys none.
 *
 * Written in Java on purpose: the stand-in driver app runs in the test package's own process,
 * where the Kotlin standard library is not available.
 */
public final class FakeRtlTcp implements Closeable {
    private final ServerSocket server;
    private final int sampleRate;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final List<Integer> commands = new CopyOnWriteArrayList<>();
    private volatile int connections;

    public FakeRtlTcp() throws IOException {
        this(0, 2_400_000);
    }

    public FakeRtlTcp(int port, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        Thread accept = new Thread(this::accept, "fake-rtl_tcp");
        accept.setDaemon(true);
        accept.start();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    /** Command codes received, in order. */
    public List<Integer> getCommands() {
        return commands;
    }

    public int getConnections() {
        return connections;
    }

    /** Cuts the connections that are open, as a server restart or a network loss would. */
    public void dropClients() {
        for (Socket c : clients) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        clients.clear();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        dropClients();
    }

    private void accept() {
        while (!server.isClosed()) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            connections++;
            clients.add(socket);
            Thread stream = new Thread(() -> {
                try {
                    stream(socket);
                } catch (IOException | InterruptedException ignored) {
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "fake-rtl_tcp-stream");
            stream.setDaemon(true);
            stream.start();
        }
    }

    private void stream(Socket socket) throws IOException, InterruptedException {
        socket.setTcpNoDelay(true);
        OutputStream out = socket.getOutputStream();
        out.write(new byte[] {'R', 'T', 'L', '0', 0, 0, 0, 5, 0, 0, 0, 29});
        Thread reader = new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (true) {
                    int code = in.readUnsignedByte();
                    in.readInt();
                    commands.add(code);
                }
            } catch (IOException ignored) {
            }
        }, "fake-rtl_tcp-commands");
        reader.setDaemon(true);
        reader.start();

        int samples = sampleRate / 100;   // 10 ms; the carrier at fs/8 fits a whole number of turns
        byte[] block = new byte[2 * samples];
        Random random = new Random(7);
        for (int n = 0; n < samples; n++) {
            double phase = 2 * Math.PI * n / 8;
            double i = 0.3 * Math.cos(phase) + 0.02 * random.nextGaussian();
            double q = 0.3 * Math.sin(phase) + 0.02 * random.nextGaussian();
            block[2 * n] = (byte) Math.max(0, Math.min(255, (int) (127.5 + 127.5 * i)));
            block[2 * n + 1] = (byte) Math.max(0, Math.min(255, (int) (127.5 + 127.5 * q)));
        }
        long start = System.nanoTime();
        long sent = 0;
        while (!socket.isClosed()) {
            out.write(block);
            sent += samples;
            long wait = start + sent * 1_000_000_000L / sampleRate - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
    }
}
