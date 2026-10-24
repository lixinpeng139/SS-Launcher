package com.sslauncher.multiplayer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One unidirectional voice stream over UDP/KCP. Frames are one KCP message
 * each, so no length prefix is needed; KCP preserves message boundaries.
 */
final class KcpVoiceSession implements VoiceStream {
    private static final int MAX_FRAME = 4096;

    private final UdpTransport.KcpLink link;
    private final String peerId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeHandled = new AtomicBoolean();
    private volatile Consumer<byte[]> frameHandler;
    private volatile Runnable closeHandler;

    KcpVoiceSession(UdpTransport transport, UdpTransport.KcpLink link, String peerId) {
        this.link = link;
        this.peerId = peerId;
    }

    @Override
    public String remotePeerId() {
        return peerId;
    }

    @Override
    public void setFrameHandler(Consumer<byte[]> handler) {
        this.frameHandler = handler;
    }

    @Override
    public void setCloseHandler(Runnable handler) {
        this.closeHandler = handler;
        if (handler != null && closed.get()) {
            runCloseHandler(handler);
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void writeFrame(byte[] payload) throws IOException {
        if (closed.get()) {
            throw new IOException("语音流已关闭");
        }
        if (payload == null || payload.length == 0 || payload.length > MAX_FRAME) {
            return;
        }
        try {
            // Droppable when congested: blocking audio would add latency for
            // every listener behind this stream.
            link.sendMessage(UdpTransport.MSG_DATA, payload, false);
        } catch (IOException e) {
            markClosed();
            throw e;
        }
    }

    @Override
    public void close() {
        link.close(true);
    }

    void acceptFrame(byte[] frame) {
        if (frame == null || frame.length == 0 || closed.get()) {
            return;
        }
        Consumer<byte[]> handler = frameHandler;
        if (handler != null) {
            try {
                handler.accept(frame);
            } catch (Throwable ignored) {
                // A broken audio sink must not tear down the stream.
            }
        }
    }

    /** Called by the link while it holds its own lock. */
    void linkClosed() {
        markClosed();
    }

    private void markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable handler = closeHandler;
        if (handler != null) {
            runCloseHandler(handler);
        }
    }

    private void runCloseHandler(Runnable handler) {
        if (!closeHandled.compareAndSet(false, true)) {
            return;
        }
        try {
            handler.run();
        } catch (Throwable ignored) {
            // Cleanup must not throw back into the transport.
        }
    }
}
