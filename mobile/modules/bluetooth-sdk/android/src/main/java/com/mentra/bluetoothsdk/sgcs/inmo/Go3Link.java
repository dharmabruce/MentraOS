package com.mentra.bluetoothsdk.sgcs.inmo;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * GO3 protocol over one GATT connection: binding query, session start, and image presentation.
 * Never binds or releases: that is RFCOMM-only and needs explicit user confirmation, which this SDK
 * doesn't offer yet. Blocking; one worker thread at a time.
 * Ported from lens-spark@79d33c2 (link/Go3Link.java and the GATT half of link/Connector.java).
 */
public final class Go3Link implements Closeable {
    /** A raw byte pipe to the glasses. {@link #close()} may be called from any thread. */
    public interface Transport extends Closeable {
        /** Connects. Inbound bytes go to {@code inbound}; {@code onLost} runs if the link drops after opening. */
        void open(Consumer<byte[]> inbound, Runnable onLost) throws IOException;

        /** Each {@code write(byte[], int, int)} must carry exactly one AA55 frame. */
        OutputStream output();

        @Override void close();
    }

    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    /**
     * With INMO Global connected, the glasses may answer the binding query on its connection instead of
     * ours (measured 2026-10-14). After this long without an answer, start a session without login.
     */
    static final long QUERY_TIMEOUT_MS = 3000;

    /** The glasses didn't answer a binding query in time. */
    public static final class NoAnswer extends IOException {
        NoAnswer() { super("The glasses didn't answer the binding check."); }
    }

    private final Transport transport;
    private final Sleeper sleeper;
    private final Go3Protocol protocol = new Go3Protocol();
    private final ScreenStatusDecoder screen;
    private final BlockingQueue<BindingReply> replies = new ArrayBlockingQueue<>(1);
    private BindingResponseDecoder query;
    private boolean presentedFirstImage;
    private volatile boolean closed;

    /** {@code onScreen} receives glasses display status reports (0 = off, 1 = on). */
    public Go3Link(Transport transport, Sleeper sleeper, IntConsumer onScreen) {
        this.transport = transport;
        this.sleeper = sleeper;
        this.screen = new ScreenStatusDecoder(onScreen);
    }

    /** Opens the transport and enables the protocol bridge on it. */
    public void open(Runnable onLost) throws IOException {
        transport.open(this::inbound, onLost);
        protocol.enableGattOverBredr(out());
    }

    private void inbound(byte[] bytes) {
        screen.accept(bytes, bytes.length);
        synchronized (this) {
            if (query == null) return;
            query.accept(bytes, bytes.length);
            BindingReply reply = query.takeReply();
            if (reply != null && !replies.offer(reply)) reply.close();
        }
    }

    /** Operation 0. The caller owns (and must close) the returned reply. */
    public BindingReply queryBinding(long timeoutMs) throws IOException {
        synchronized (this) {
            replies.clear();
            query = new BindingResponseDecoder();
        }
        try {
            protocol.queryBindState(out());
            BindingReply reply = replies.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (closed) throw new IOException("Connection closed");
            if (reply == null) throw new NoAnswer();
            return reply;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled");
        } finally {
            synchronized (this) {
                if (query != null) query.close();
                query = null;
            }
        }
    }

    /**
     * Checks the binding on this (dedicated) link and returns the existing identifier to log in with,
     * or null for a no-login session. The caller closes this link afterwards and wipes the result.
     */
    public byte[] checkBinding() throws IOException {
        try (BindingReply reply = queryBinding(QUERY_TIMEOUT_MS)) {
            if (reply.kind == BindingReply.Kind.ACCESS_FOUND) return reply.copyIdentifier();
            if (reply.kind == BindingReply.Kind.UNBOUND) return null;
            throw new IOException(unexpected(reply.kind));
        } catch (NoAnswer noAnswer) {
            return null;
        }
    }

    /** Session start: bridge already enabled by open(); login only when a registration exists. */
    public void startSession(byte[] existingIdentifier) throws IOException {
        pause(300);
        if (existingIdentifier != null) {
            protocol.login(out(), existingIdentifier);
            pause(600);
        }
        protocol.advertiseNavigation(out());
    }

    /** Shows one PNG: Open–image–Open for the first image on this link, image–Open after that. */
    public void showImage(byte[] png) throws IOException {
        boolean first = !presentedFirstImage;
        try {
            if (first) protocol.openNavigation(out());
            protocol.destinationArrived(out(), png);
            protocol.openNavigation(out());
            presentedFirstImage = true;
        } catch (IOException failure) {
            presentedFirstImage = false;
            throw failure;
        }
    }

    /** Heartbeat: the read-only binding query, without waiting for its reply. */
    public void ping() throws IOException {
        protocol.queryBindState(out());
    }

    private OutputStream out() throws IOException {
        if (closed) throw new IOException("Connection closed");
        return transport.output();
    }

    private void pause(long millis) throws IOException {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled");
        }
        if (closed) throw new IOException("Connection closed");
    }

    private static String unexpected(BindingReply.Kind kind) {
        switch (kind) {
            case VENDOR_ERROR: return "The glasses refused the binding check.";
            case IDENTIFIER_MISSING: return "The glasses reported a binding without an identifier.";
            default: return "The glasses gave an unexpected binding answer.";
        }
    }

    @Override public void close() {
        closed = true;
        transport.close();
        screen.close();
        synchronized (this) {
            if (query != null) query.close();
            query = null;
            BindingReply pending = replies.poll();
            if (pending != null) pending.close();
        }
    }

    /** Wipes an identifier returned by {@link #checkBinding()}. */
    public static void wipe(byte[] identifier) {
        if (identifier != null) Arrays.fill(identifier, (byte) 0);
    }
}
