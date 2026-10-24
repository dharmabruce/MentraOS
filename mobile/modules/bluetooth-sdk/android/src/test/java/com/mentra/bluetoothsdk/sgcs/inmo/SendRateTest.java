package com.mentra.bluetoothsdk.sgcs.inmo;

import org.junit.Before;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/** docs/SPEC.md "Sending": pacing, climb, hold, backlog cut and drain, marks, blocked writes and drops. */
public final class SendRateTest {
    private static final long MS = 1_000_000;
    private static final double EPSILON = 1e-9;

    private final SendRate rate = new SendRate();
    private long now = 5_000 * MS;

    @Before public void connect() {
        rate.connectionStarted(now);
    }

    /** One 5 KB frame of 22 writes, carried at {@code deliveredKbps}, its confirmation back after {@code rttMs}. */
    private void frame(double deliveredKbps, long rttMs) {
        now += 150 * MS;
        long bytes = 5 * 1024;
        rate.confirmed(rttMs, 22, bytes, (long) (bytes / 1024.0 / deliveredKbps * 1e9), now);
    }

    @Test public void imageFragmentsGoUnconfirmedAndTheOpenAfterIsConfirmed() throws Exception {
        List<byte[]> writes = new ArrayList<>();
        OutputStream out = new OutputStream() {
            @Override public void write(int b) { throw new UnsupportedOperationException(); }
            @Override public void write(byte[] b, int offset, int length) { writes.add(Arrays.copyOfRange(b, offset, offset + length)); }
        };
        Go3Protocol protocol = new Go3Protocol();
        protocol.destinationArrived(out, new byte[5000]);
        int image = writes.size();
        protocol.openNavigation(out);
        protocol.advertiseNavigation(out);
        for (int i = 0; i < image; i++) assertFalse(SendRate.confirms(writes.get(i)));
        assertTrue("Open after the image", SendRate.confirms(writes.get(image)));
        assertTrue("session setup", SendRate.confirms(writes.get(image + 1)));
    }

    @Test public void startsAt30() {
        assertEquals(30, rate.targetKbps(), EPSILON);
    }

    @Test public void climbsAPointThreePerFrameWhileDeliveryKeepsUp() {
        for (int i = 0; i < 10; i++) frame(30, 20);
        assertEquals(33.0, rate.targetKbps(), 1e-6);
        assertEquals(SendRate.State.CLIMB, rate.state());
    }

    @Test public void climbsOnlyWithin15PercentOfDelivered() {
        frame(20, 20);
        assertEquals(23, rate.targetKbps(), 1e-6);
    }

    @Test public void holdsWhileRoundTripsRunOverOneAndAHalfTimesUsual() {
        frame(30, 20);
        double before = rate.targetKbps();
        frame(30, 31);
        assertEquals(before, rate.targetKbps(), EPSILON);
        assertEquals(SendRate.State.HOLD, rate.state());
    }

    @Test public void backlogCutsOnceToDeliveredAndSetsTheSoftMarkThenDrains() {
        frame(30, 20);
        frame(30, 100); // over 2 x 20 + 50
        assertEquals(30 * 0.85, rate.targetKbps(), 1e-6);
        assertEquals(30, rate.softKbps(), 1e-6);
        assertEquals(SendRate.State.DRAIN, rate.state());
        frame(30, 100);
        assertEquals("no further judgement while draining", 30 * 0.85, rate.targetKbps(), 1e-6);
        frame(30, 20);
        assertEquals("judging resumes once round trips are back near usual", 30 * 0.85 + 0.3, rate.targetKbps(), 1e-6);
    }

    @Test public void drainEndsAfterTenSecondsAtMost() {
        frame(30, 20);
        frame(30, 100);
        double cut = rate.targetKbps();
        now += 10_000 * MS;
        frame(30, 100);
        assertTrue(rate.targetKbps() < cut);
    }

    @Test public void blockedWriteHalvesAndSetsTheHardMark() {
        frame(30, 20);
        double before = rate.targetKbps();
        rate.blocked(now);
        assertEquals(before / 2, rate.targetKbps(), EPSILON);
        assertEquals(before, rate.hardKbps(), EPSILON);
    }

    @Test public void dropResumesAtHalfAndRateAndMarksSurviveTheReconnect() {
        frame(30, 20);
        double before = rate.targetKbps();
        rate.dropped();
        rate.connectionStarted(now);
        assertEquals(before / 2, rate.targetKbps(), EPSILON);
        assertEquals(before, rate.hardKbps(), EPSILON);
    }

    @Test public void climbIsAFifthFrom85PercentOfTheHardMarkAndKeepsGoingPastIt() {
        frame(30, 20);
        rate.blocked(now);
        double hard = rate.hardKbps();
        while (rate.targetKbps() <= hard * 0.85) frame(30, 20);
        double before = rate.targetKbps();
        frame(30, 20);
        assertEquals(before + 0.3 / 5, rate.targetKbps(), EPSILON);
        while (rate.targetKbps() <= hard) frame(30, 20);
        before = rate.targetKbps();
        frame(30, 20);
        assertEquals(before + 0.3 / 5, rate.targetKbps(), EPSILON);
    }

    @Test public void climbIsAThirdFrom90PercentOfTheSoftMark() {
        frame(30, 20);
        frame(30, 100);
        double soft = rate.softKbps();
        while (rate.targetKbps() <= soft * 0.9) frame(30, 20);
        double before = rate.targetKbps();
        frame(30, 20);
        assertEquals(before + 0.3 / 3, rate.targetKbps(), EPSILON);
    }

    @Test public void softMarkLoosensFivePercentEvery20Seconds() {
        frame(30, 20);
        frame(30, 100);
        double soft = rate.softKbps();
        now += 19_000 * MS;
        frame(30, 20);
        assertEquals(soft, rate.softKbps(), EPSILON);
        now += 2_000 * MS;
        frame(30, 20);
        assertEquals(soft * 1.05, rate.softKbps(), EPSILON);
    }

    @Test public void hardMarkLoosensFivePercentEvery30Seconds() {
        frame(30, 20);
        rate.blocked(now);
        double hard = rate.hardKbps();
        now += 29_000 * MS;
        frame(30, 20);
        assertEquals(hard, rate.hardKbps(), EPSILON);
        now += 2_000 * MS;
        frame(30, 20);
        assertEquals(hard * 1.05, rate.hardKbps(), EPSILON);
    }

    @Test public void setupMessagesDoNotSteer() {
        rate.confirmed(500, 1, 20, MS, now);
        assertEquals(30, rate.targetKbps(), EPSILON);
        assertEquals(SendRate.State.CLIMB, rate.state());
    }

    @Test public void writesArePacedToTheTarget() {
        assertEquals(0, rate.waitNanos(3 * 1024, now));
        // 3 KB at 30 KB/s is 100 ms.
        assertEquals(100 * MS, rate.waitNanos(230, now), MS / 100);
    }

    @Test public void idleTimeEarnsAtMostOneUsualRoundTripOfCredit() {
        frame(30, 20);
        now += 1000 * MS;
        assertTrue(rate.waitNanos(3 * 1024, now) <= 0);
        double expected = 3 * 1024 / (rate.targetKbps() * 1024) * 1e9 - 20 * MS;
        assertEquals(expected, rate.waitNanos(230, now), MS / 100);
    }
}
