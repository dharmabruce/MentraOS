package com.mentra.bluetoothsdk.sgcs.inmo;

import java.util.Locale;

/**
 * The adaptive sender from lens-spark docs/SPEC.md "Sending": a target rate in KB/s that paces every GATT write, steered by
 * the round trip of the one confirmed write per frame. Pure Java; times are {@code System.nanoTime()} values
 * passed in. One instance lives as long as the process, so the rate and marks survive reconnects. Thread-safe.
 * Ported from lens-spark@3e2158f (link/SendRate.java), originally the auto+open mode of
 * experiment/gatt-write-v2@d9ad33d (link/GattChannel.java).
 */
public final class SendRate {
    static final double START_KBPS = 30, MIN_KBPS = 4, MAX_KBPS = 200, CLIMB_KBPS = 0.3;
    /** Climb only while the target is within 15% of the rate actually delivered. */
    static final double HEADROOM = 1.15;
    /** A round trip over this many times usual is the early sign of a backlog: hold. */
    static final double RISING = 1.5;
    /** A round trip over 2x usual plus this is a backlog: cut. */
    static final long BACKLOG_EXTRA_MS = 50;
    static final double BACKLOG_CUT = 0.85;
    static final long MAX_DRAIN_MS = 10_000;
    /** A write blocked this long (the glasses went silent and came back) halves the rate. */
    public static final long BLOCKED_MS = 1000;
    static final long SOFT_LOOSEN_MS = 20_000, HARD_LOOSEN_MS = 30_000;
    static final double LOOSEN = 1.05;
    /** A confirmation covering fewer writes than this ends a session setup message, not a frame. */
    public static final int MIN_FRAME_WRITES = 4;
    /** Idle time beyond one usual round trip (at most this) earns no credit toward the next write. */
    private static final double MAX_CREDIT_MS = 100;

    public enum State { CLIMB, HOLD, DRAIN }

    private double kbps = START_KBPS, softKbps = MAX_KBPS, hardKbps = MAX_KBPS;
    // Per connection (reset by connectionStarted).
    private double usualRttMs, deliveredKbps;
    private long nextSendAt = Long.MIN_VALUE, softAt, hardAt, cutAt;
    private boolean draining;
    private long lastRttMs;
    private State state = State.CLIMB;

    /**
     * Whether this AA55 frame (one GATT write) is sent confirmed: the last fragment of a short message, so the Open
     * after each image (one confirmation per frame) and each session setup message; never an image fragment.
     */
    public static boolean confirms(byte[] frame) {
        int count = u16(frame, 4), index = u16(frame, 6);
        return index + 1 == count && count < MIN_FRAME_WRITES;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 255) << 8) | (b[i + 1] & 255);
    }

    /** A new connection: forget its round trips and delivery, keep the rate and the marks. */
    public synchronized void connectionStarted(long now) {
        usualRttMs = 0;
        deliveredKbps = 0;
        nextSendAt = Long.MIN_VALUE;
        softAt = hardAt = now;
        draining = false;
        lastRttMs = 0;
        state = State.CLIMB;
    }

    /**
     * How long to wait (ns) before writing {@code bytes}, and books them. The whole frame, writes and the wait for
     * its confirmation, runs at the target: time spent waiting counts, but idle time beyond one usual round trip
     * (at most 100 ms) earns no credit.
     */
    public synchronized long waitNanos(int bytes, long now) {
        long credit = (long) (Math.min(usualRttMs, MAX_CREDIT_MS) * 1e6);
        long start = Math.max(now - credit, nextSendAt);
        nextSendAt = start + (long) (bytes * 1e9 / (kbps * 1024));
        return start - now;
    }

    /** A write was blocked over {@link #BLOCKED_MS}: halve, and the rate before becomes the hard mark. */
    public synchronized void blocked(long now) {
        hardKbps = kbps;
        hardAt = now;
        cut(now, kbps * 0.5);
    }

    /** The link dropped: the reconnect resumes at half, and the rate it dropped at becomes the hard mark. */
    public synchronized void dropped() {
        hardKbps = kbps;
        kbps = Math.max(MIN_KBPS, kbps * 0.5);
    }

    /**
     * A confirmed write's round trip. {@code frameWrites} and {@code frameBytes} are everything that confirmation
     * covers, written over {@code frameNanos} (first write to confirmation).
     */
    public synchronized void confirmed(long rttMs, int frameWrites, long frameBytes, long frameNanos, long now) {
        lastRttMs = rttMs;
        if (frameWrites < MIN_FRAME_WRITES) return;
        if (frameBytes > 0 && frameNanos > 0) {
            double delivered = frameBytes / 1024.0 / (frameNanos / 1e9);
            deliveredKbps = deliveredKbps == 0 ? delivered : deliveredKbps * 0.8 + delivered * 0.2;
        }
        if (usualRttMs == 0) usualRttMs = rttMs;
        if (draining) {
            // After a cut, wait for the backlog to drain (round trips back near usual) before judging again.
            if (rttMs > usualRttMs * RISING && now - cutAt <= MAX_DRAIN_MS * 1_000_000) return;
            draining = false;
        }
        if (rttMs > 2 * usualRttMs + BACKLOG_EXTRA_MS) {
            // A backlog: cut once to just under what the link was carrying, mark that level softly, then drain.
            double carried = Math.min(kbps, deliveredKbps);
            softKbps = carried;
            softAt = now;
            cut(now, carried * BACKLOG_CUT);
            return;
        }
        if (rttMs > usualRttMs * RISING) {
            // Round trips creeping up: hold. "Usual" follows only slowly, so a lasting change (audio starting)
            // is eventually accepted as the new normal.
            usualRttMs = usualRttMs * 0.98 + rttMs * 0.02;
            state = State.HOLD;
            return;
        }
        usualRttMs = usualRttMs * 0.9 + rttMs * 0.1;
        state = State.CLIMB;
        if (kbps < deliveredKbps * HEADROOM) {
            double step = CLIMB_KBPS;
            if (kbps > hardKbps * 0.85) step = Math.min(step, CLIMB_KBPS / 5);
            if (kbps > softKbps * 0.9) step = Math.min(step, CLIMB_KBPS / 3);
            kbps = Math.min(MAX_KBPS, kbps + step);
        } else {
            // A target far above what's delivered holds nothing back.
            kbps = Math.max(MIN_KBPS, deliveredKbps * HEADROOM);
        }
        // Marks loosen over time, so one left by a passing problem stops slowing the climb.
        if (now - hardAt > HARD_LOOSEN_MS * 1_000_000) {
            hardKbps = Math.min(MAX_KBPS, hardKbps * LOOSEN);
            hardAt = now;
        }
        if (now - softAt > SOFT_LOOSEN_MS * 1_000_000) {
            softKbps = Math.min(MAX_KBPS, softKbps * LOOSEN);
            softAt = now;
        }
    }

    private void cut(long now, double to) {
        kbps = Math.max(MIN_KBPS, to);
        cutAt = now;
        draining = true;
        state = State.DRAIN;
    }

    public synchronized double targetKbps() { return kbps; }
    public synchronized double deliveredKbps() { return deliveredKbps; }
    public synchronized double softKbps() { return softKbps; }
    public synchronized double hardKbps() { return hardKbps; }
    public synchronized State state() { return state; }

    /** Numbers and state names only, for the per-frame log line. */
    public synchronized String describe() {
        return String.format(Locale.ROOT, "targetKbps=%.1f deliveredKbps=%.1f rttMs=%d usualRttMs=%.1f softKbps=%.1f hardKbps=%.1f state=%s",
                kbps, deliveredKbps, lastRttMs, usualRttMs, softKbps, hardKbps, state);
    }
}
