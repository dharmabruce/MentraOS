package com.mentra.bluetoothsdk.sgcs.inmo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static com.mentra.bluetoothsdk.sgcs.inmo.Fixtures.*;
import static org.junit.Assert.*;

public final class Go3LinkTest {
    private static final byte[] ENABLE = {0x10, 0x14, (byte) 0xa2, 0x01, 0x02, 0x50, 0x00};
    private static final byte[] QUERY = {0x10, 0x18, (byte) 0xda, 0x01, 0x02, 0x08, 0x00};
    private static final byte[] ADVERTISE = {0x10, 0x23, (byte) 0xb2, 0x02, 0x03, 0x0a, 0x01, 0x02};
    private static final byte[] OPEN = {0x10, 0x0f, (byte) 0x92, 0x01, 0x02, 0x08, 0x0d};

    @Test public void openEnablesTheGattBridge() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        open(transport);
        assertEquals(1, transport.messages().size());
        assertArrayEquals(ENABLE, transport.messages().get(0));
    }

    @Test public void checkBindingReturnsTheExistingIdentifier() throws Exception {
        byte[] id = {'A', 'B', 'C'};
        Go3Link link = open(new FakeTransport(frame(9, 1, 0, found(id))));
        byte[] existing = link.checkBinding();
        assertArrayEquals(id, existing);
    }

    @Test public void checkBindingReturnsNullWhenUnbound() throws Exception {
        Go3Link link = open(new FakeTransport(frame(9, 1, 0, response(new byte[0]))));
        assertNull(link.checkBinding());
    }

    @Test public void unansweredQueryTimesOut() throws Exception {
        Go3Link link = open(new FakeTransport(null));
        try {
            link.queryBinding(20).close();
            fail();
        } catch (Go3Link.NoAnswer expected) {
        }
    }

    @Test public void vendorErrorFailsTheBindingCheck() throws Exception {
        Go3Link link = open(new FakeTransport(frame(9, 1, 0, response(new byte[]{0x18, 0x05}))));
        try {
            link.checkBinding();
            fail();
        } catch (java.io.IOException expected) {
            assertFalse(expected instanceof Go3Link.NoAnswer);
        }
    }

    @Test public void sessionWithoutRegistrationSkipsLogin() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        open(transport).startSession(null);
        List<byte[]> messages = transport.messages();
        assertEquals(2, messages.size());
        assertArrayEquals(ADVERTISE, messages.get(1));
    }

    @Test public void sessionWithRegistrationLogsInFirst() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        byte[] id = {'A', 'B', 'C'};
        open(transport).startSession(id);
        List<byte[]> messages = transport.messages();
        assertEquals(3, messages.size());
        assertArrayEquals(new byte[]{0x10, 0x18, (byte) 0xda, 0x01, 0x07, 0x08, 0x01, 0x12, 0x03, 'A', 'B', 'C'}, messages.get(1));
        assertArrayEquals(ADVERTISE, messages.get(2));
    }

    @Test public void firstImageIsWrappedInOpensAndLaterOnesFollowedByOne() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        Go3Link link = open(transport);
        link.showImage(new byte[300]);
        link.showImage(new byte[10]);
        List<byte[]> messages = transport.messages();
        assertEquals(6, messages.size());
        assertArrayEquals(OPEN, messages.get(1));
        assertEquals(0x21, messages.get(2)[1]);
        assertArrayEquals(OPEN, messages.get(3));
        assertEquals(0x21, messages.get(4)[1]);
        assertArrayEquals(OPEN, messages.get(5));
    }

    @Test public void writesFailAfterClose() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        Go3Link link = open(transport);
        link.close();
        assertTrue(transport.closed);
        try {
            link.showImage(new byte[1]);
            fail();
        } catch (java.io.IOException expected) {
        }
    }

    private static Go3Link open(FakeTransport transport) throws Exception {
        Go3Link link = new Go3Link(transport, millis -> { }, screen -> { });
        link.open(() -> { });
        return link;
    }

    /** Records each written frame; answers the binding query with {@code reply} when set. */
    private static final class FakeTransport implements Go3Link.Transport {
        final List<byte[]> frames = new ArrayList<>();
        final byte[] reply;
        Consumer<byte[]> inbound;
        boolean closed;

        FakeTransport(byte[] reply) {
            this.reply = reply;
        }

        @Override public void open(Consumer<byte[]> inbound, Runnable onLost) {
            this.inbound = inbound;
        }

        @Override public OutputStream output() {
            return new OutputStream() {
                @Override public void write(int b) {
                    throw new UnsupportedOperationException();
                }

                @Override public void write(byte[] bytes, int offset, int length) {
                    byte[] frame = Arrays.copyOfRange(bytes, offset, offset + length);
                    frames.add(frame);
                    byte[] payload = Arrays.copyOfRange(frame, 12, frame.length - 2);
                    if (reply != null && Arrays.equals(QUERY, payload)) inbound.accept(reply);
                }
            };
        }

        @Override public void close() {
            closed = true;
        }

        /** Frames reassembled into message payloads, in order. */
        List<byte[]> messages() {
            List<byte[]> messages = new ArrayList<>();
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            for (byte[] frame : frames) {
                int count = ((frame[4] & 255) << 8) | (frame[5] & 255);
                int index = ((frame[6] & 255) << 8) | (frame[7] & 255);
                current.write(frame, 12, frame.length - 14);
                if (index == count - 1) {
                    messages.add(current.toByteArray());
                    current.reset();
                }
            }
            return messages;
        }
    }
}
