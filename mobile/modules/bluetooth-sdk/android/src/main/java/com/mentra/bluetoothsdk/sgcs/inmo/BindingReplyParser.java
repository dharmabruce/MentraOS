package com.mentra.bluetoothsdk.sgcs.inmo;

import java.util.Arrays;

/**
 * Minimal bounded protobuf parser for the binding query reply: type 24, field 27, operation 3,
 * status field 4 = {1: bound, 2: identifier, 3: error}. Unknown or ambiguous shapes fail closed.
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/setup/BindingReplyParser.java
 * (diagnostic reason codes removed; classification unchanged).
 * Ported from lens-spark@79d33c2 (protocol/BindingReplyParser.java).
 */
public final class BindingReplyParser {
    public static final int MAX_MESSAGE = 4096;

    public static BindingReply parse(byte[] message) {
        if (message == null || message.length > MAX_MESSAGE) return new BindingReply(BindingReply.Kind.LIMIT_EXCEEDED);
        try {
            Reader root = new Reader(message, 0, message.length);
            Long type = null;
            Reader bind = null;
            boolean foreignRoot = false;
            while (root.more()) {
                int tag = root.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 2) {
                    if (type != null || wire != 0) throw new Invalid();
                    type = root.scalar();
                } else if (field == 27) {
                    if (bind != null || wire != 2) throw new Invalid();
                    bind = root.nested(MAX_MESSAGE);
                } else if (field == 1) {
                    if (wire != 0) throw new Invalid();
                    root.scalar();
                } else {
                    foreignRoot = true;
                    root.skip(wire);
                }
            }
            if (type == null || type != 24 || bind == null) return new BindingReply(BindingReply.Kind.UNRELATED);
            if (foreignRoot) throw new Invalid();

            Long operation = null;
            Reader status = null;
            boolean foreignBind = false;
            while (bind.more()) {
                int tag = bind.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 1) {
                    if (operation != null || wire != 0) throw new Invalid();
                    operation = bind.scalar();
                } else if (field == 4) {
                    if (status != null || wire != 2) throw new Invalid();
                    status = bind.nested(MAX_MESSAGE);
                } else {
                    foreignBind = true;
                    bind.skip(wire);
                }
            }
            if (operation == null || operation != 3) return new BindingReply(BindingReply.Kind.UNRELATED);
            if (foreignBind) throw new Invalid();
            if (status == null) return new BindingReply(BindingReply.Kind.IDENTIFIER_MISSING);
            // Vendor implicit-presence default: a present, exactly empty status means unbound.
            if (!status.more()) return new BindingReply(BindingReply.Kind.UNBOUND);

            Long bound = null, error = null;
            Reader id = null;
            while (status.more()) {
                int tag = status.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 1) {
                    if (bound != null || wire != 0) throw new Invalid();
                    bound = status.scalar();
                    if (bound > 1) throw new Invalid();
                } else if (field == 2) {
                    if (id != null || wire != 2) throw new Invalid();
                    id = status.nested(AccessIdentifier.MAX_LENGTH);
                } else if (field == 3) {
                    if (error != null || wire != 0) throw new Invalid();
                    error = status.scalar();
                } else {
                    status.skip(wire);
                }
            }
            if (error != null && error != 0) return new BindingReply(BindingReply.Kind.VENDOR_ERROR);
            if (bound == null) return new BindingReply(BindingReply.Kind.MALFORMED);
            if (bound == 0) return new BindingReply(BindingReply.Kind.UNBOUND);
            if (id == null || id.end == id.position) return new BindingReply(BindingReply.Kind.IDENTIFIER_MISSING);
            byte[] value = Arrays.copyOfRange(message, id.position, id.end);
            if (!AccessIdentifier.valid(value)) {
                Arrays.fill(value, (byte) 0);
                return new BindingReply(BindingReply.Kind.MALFORMED);
            }
            return new BindingReply(BindingReply.Kind.ACCESS_FOUND, value);
        } catch (Invalid invalid) {
            return new BindingReply(BindingReply.Kind.MALFORMED);
        }
    }

    static final class Invalid extends IllegalArgumentException { }

    /** Bounded protobuf reader; rejects non-canonical and oversized varints. Shared with ScreenStatusParser. */
    static final class Reader {
        final byte[] bytes;
        final int end;
        int position;

        Reader(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        boolean more() {
            return position < end;
        }

        int tag() {
            long value = scalar();
            if (value == 0 || value > 0xffffffffL || (value >>> 3) == 0) throw new Invalid();
            return (int) value;
        }

        long scalar() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                if (position >= end) throw new Invalid();
                int b = bytes[position++] & 255;
                if (i == 4 && (b & 0xf0) != 0) throw new Invalid();
                value |= (long) (b & 127) << (7 * i);
                if ((b & 128) == 0) {
                    if (i > 0 && b == 0) throw new Invalid();
                    return value;
                }
            }
            throw new Invalid();
        }

        Reader nested(int max) {
            long count = scalar();
            if (count > max || count > end - position) throw new Invalid();
            Reader result = new Reader(bytes, position, position + (int) count);
            position += (int) count;
            return result;
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    for (int i = 0; i < 10; i++) {
                        if (position >= end) throw new Invalid();
                        int b = bytes[position++] & 255;
                        if (i == 9 && (b & 0xfe) != 0) throw new Invalid();
                        if ((b & 128) == 0) return;
                    }
                    throw new Invalid();
                case 1: advance(8); return;
                case 2: nested(MAX_MESSAGE); return;
                case 5: advance(4); return;
                default: throw new Invalid();
            }
        }

        void advance(int count) {
            if (count > end - position) throw new Invalid();
            position += count;
        }
    }

    private BindingReplyParser() { }
}
