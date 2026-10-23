package com.mentra.bluetoothsdk.sgcs.inmo;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Watches all inbound traffic for glasses display on/off reports. Bad frames resynchronize instead of
 * failing, so stray traffic can never fake an "off" report or block a later valid one.
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/screen/ScreenStatusDecoder.java
 * (diagnostics removed; behavior unchanged).
 * Ported from lens-spark@79d33c2 (protocol/ScreenStatusDecoder.java).
 */
public final class ScreenStatusDecoder implements AutoCloseable {
    private final byte[] pending = new byte[BindingReplyParser.MAX_MESSAGE + 14];
    private final Assembly[] assemblies = {new Assembly(), new Assembly()};
    private final IntConsumer observer;
    private int used;
    private boolean closed;

    private static final class Assembly {
        final byte[] message = new byte[BindingReplyParser.MAX_MESSAGE];
        int length, sequence = -1, fragments, nextIndex;

        void clear() {
            Arrays.fill(message, (byte) 0);
            length = 0;
            sequence = -1;
            fragments = 0;
            nextIndex = 0;
        }
    }

    /** {@code observer} receives {@link ScreenStatusParser#OFF} or {@link ScreenStatusParser#ON}. */
    public ScreenStatusDecoder(IntConsumer observer) {
        this.observer = observer;
    }

    public synchronized void accept(byte[] bytes, int count) {
        if (closed) return;
        if (count < 0 || count > bytes.length) throw new IllegalArgumentException("Invalid byte count");
        for (int i = 0; i < count; i++) {
            pending[used++] = bytes[i];
            parse();
        }
    }

    private void parse() {
        while (used >= 2) {
            if ((pending[0] & 255) != 0xaa || (pending[1] & 255) != 0x55) {
                discard(1);
                continue;
            }
            if (used < 12) return;
            int length = u16(10), count = u16(4), index = u16(6), seq = u16(2);
            int source = pending[8] & 255, destination = pending[9] & 255;
            if (length > BindingReplyParser.MAX_MESSAGE || count == 0 || count > 32 || index >= count) {
                resetAll();
                discard(1);
                continue;
            }
            if (used < length + 14) return;
            if (Go3Protocol.crc16(pending, length + 12) != u16(length + 12)) {
                resetAll();
                discard(1);
                continue;
            }
            if ((source != 1 && source != 2) || destination != 3) {
                resetAll();
                discard(length + 14);
                continue;
            }
            Assembly current = assemblies[source - 1];
            if (index == 0) {
                current.clear();
                current.sequence = seq;
                current.fragments = count;
            }
            if (seq != current.sequence || count != current.fragments || index != current.nextIndex
                    || current.length + length > current.message.length) {
                current.clear();
                discard(length + 14);
                continue;
            }
            System.arraycopy(pending, 12, current.message, current.length, length);
            current.length += length;
            current.nextIndex++;
            discard(length + 14);
            if (current.nextIndex == current.fragments) {
                byte[] complete = Arrays.copyOf(current.message, current.length);
                int result;
                try {
                    result = ScreenStatusParser.parse(complete);
                } finally {
                    Arrays.fill(complete, (byte) 0);
                    current.clear();
                }
                if (result >= 0) observer.accept(result);
            }
        }
    }

    private void resetAll() {
        for (Assembly a : assemblies) a.clear();
    }

    private int u16(int offset) {
        return ((pending[offset] & 255) << 8) | (pending[offset + 1] & 255);
    }

    private void discard(int count) {
        System.arraycopy(pending, count, pending, 0, used - count);
        Arrays.fill(pending, used - count, used, (byte) 0);
        used -= count;
    }

    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(pending, (byte) 0);
        used = 0;
        resetAll();
    }
}
