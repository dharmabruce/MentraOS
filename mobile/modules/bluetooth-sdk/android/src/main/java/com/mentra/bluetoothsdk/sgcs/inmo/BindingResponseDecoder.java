package com.mentra.bluetoothsdk.sgcs.inmo;

import java.util.Arrays;

/**
 * Reassembles inbound AA55 frames during one binding query and yields the first typed binding reply.
 * Both documented glasses endpoints (source 1 and 2, destination 3) are CRC-checked and reassembled
 * independently. Unrelated messages (status, echoes) are skipped; framing errors fail closed as MALFORMED.
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/setup/BindingResponseDecoder.java
 * (receive tracing removed; behavior unchanged).
 * Ported from lens-spark@79d33c2 (protocol/BindingResponseDecoder.java).
 */
public final class BindingResponseDecoder implements AutoCloseable {
    public static final int MAX_WIRE_BYTES = 32768, MAX_FRAGMENTS = 32;

    private final byte[] pending = new byte[BindingReplyParser.MAX_MESSAGE + 14];
    private final Assembly[] glasses = {new Assembly(), new Assembly()};
    private int used, total;
    private BindingReply reply;
    private boolean closed;

    private static final class Assembly {
        final byte[] bytes = new byte[BindingReplyParser.MAX_MESSAGE];
        int length, sequence = -1, fragments, nextIndex;

        void clear() {
            Arrays.fill(bytes, (byte) 0);
            length = 0;
            sequence = -1;
            fragments = 0;
            nextIndex = 0;
        }
    }

    public void accept(byte[] bytes, int count) {
        if (closed) throw new IllegalStateException("Decoder closed");
        if (count < 0 || count > bytes.length) throw new IllegalArgumentException("Invalid read size");
        for (int i = 0; i < count && reply == null; i++) {
            if (++total > MAX_WIRE_BYTES) {
                fail(BindingReply.Kind.LIMIT_EXCEEDED);
                break;
            }
            pending[used++] = bytes[i];
            parse();
        }
    }

    private void parse() {
        while (used >= 2 && reply == null) {
            if ((pending[0] & 255) != 0xaa || (pending[1] & 255) != 0x55) {
                discard(1);
                continue;
            }
            if (used < 12) return;
            int length = u16(10), count = u16(4), index = u16(6), seq = u16(2);
            int source = pending[8] & 255, destination = pending[9] & 255;
            if (length > BindingReplyParser.MAX_MESSAGE || count == 0 || count > MAX_FRAGMENTS || index >= count) {
                fail(BindingReply.Kind.MALFORMED);
                return;
            }
            if (used < length + 14) return;
            if (Go3Protocol.crc16(pending, length + 12) != u16(length + 12)) {
                fail(BindingReply.Kind.MALFORMED);
                return;
            }
            if ((source != 1 && source != 2) || destination != 3) {
                fail(BindingReply.Kind.MALFORMED);
                return;
            }
            Assembly current = glasses[source - 1];
            if (current.nextIndex == 0) {
                if (index != 0) {
                    fail(BindingReply.Kind.MALFORMED);
                    return;
                }
                current.sequence = seq;
                current.fragments = count;
            }
            if (seq != current.sequence || count != current.fragments || index != current.nextIndex
                    || current.length + length > current.bytes.length) {
                fail(BindingReply.Kind.MALFORMED);
                return;
            }
            System.arraycopy(pending, 12, current.bytes, current.length, length);
            current.length += length;
            current.nextIndex++;
            discard(length + 14);
            if (current.nextIndex == current.fragments) {
                byte[] complete = Arrays.copyOf(current.bytes, current.length);
                BindingReply parsed;
                try {
                    parsed = BindingReplyParser.parse(complete);
                } finally {
                    Arrays.fill(complete, (byte) 0);
                    current.clear();
                }
                if (parsed.kind == BindingReply.Kind.UNRELATED) {
                    parsed.close();
                    continue;
                }
                reply = parsed;
            }
        }
    }

    /** The reply, once complete; ownership passes to the caller. */
    public BindingReply takeReply() {
        BindingReply value = reply;
        reply = null;
        return value;
    }

    private void fail(BindingReply.Kind kind) {
        reply = new BindingReply(kind);
        Arrays.fill(pending, (byte) 0);
        used = 0;
        for (Assembly assembly : glasses) assembly.clear();
    }

    private int u16(int offset) {
        return ((pending[offset] & 255) << 8) | (pending[offset + 1] & 255);
    }

    private void discard(int count) {
        System.arraycopy(pending, count, pending, 0, used - count);
        Arrays.fill(pending, used - count, used, (byte) 0);
        used -= count;
    }

    @Override public void close() {
        closed = true;
        Arrays.fill(pending, (byte) 0);
        for (Assembly assembly : glasses) assembly.clear();
        if (reply != null) reply.close();
        reply = null;
    }
}
