package com.mentra.bluetoothsdk.sgcs.inmo;

/**
 * Glasses display status: message type 20, field 23, operation 10, screen field 11 (empty = off, 1 = on).
 * Returns 0 (off), 1 (on), -1 (unrelated message) or -2 (malformed).
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/screen/ScreenStatusParser.java
 * Ported from lens-spark@79d33c2 (protocol/ScreenStatusParser.java).
 */
public final class ScreenStatusParser {
    public static final int OFF = 0, ON = 1, UNRELATED = -1, MALFORMED = -2;

    public static int parse(byte[] message) {
        if (message == null || message.length > BindingReplyParser.MAX_MESSAGE) return MALFORMED;
        try {
            BindingReplyParser.Reader root = new BindingReplyParser.Reader(message, 0, message.length);
            Long type = null;
            BindingReplyParser.Reader status = null;
            boolean foreign = false;
            while (root.more()) {
                int tag = root.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 2) {
                    if (type != null || wire != 0) throw new BindingReplyParser.Invalid();
                    type = root.scalar();
                } else if (field == 23) {
                    if (status != null || wire != 2) throw new BindingReplyParser.Invalid();
                    status = root.nested(BindingReplyParser.MAX_MESSAGE);
                } else if (field == 1) {
                    if (wire != 0) throw new BindingReplyParser.Invalid();
                    root.scalar();
                } else {
                    foreign = true;
                    root.skip(wire);
                }
            }
            if (type == null || type != 20 || status == null) return UNRELATED;
            if (foreign) throw new BindingReplyParser.Invalid();

            Long operation = null;
            BindingReplyParser.Reader value = null;
            boolean otherStatus = false;
            while (status.more()) {
                int tag = status.tag(), field = tag >>> 3, wire = tag & 7;
                if (field == 1) {
                    if (operation != null || wire != 0) throw new BindingReplyParser.Invalid();
                    operation = status.scalar();
                } else if (field == 11) {
                    if (value != null || wire != 2) throw new BindingReplyParser.Invalid();
                    value = status.nested(BindingReplyParser.MAX_MESSAGE);
                } else {
                    otherStatus = true;
                    status.skip(wire);
                }
            }
            if (operation == null || operation != 10) return UNRELATED;
            if (value == null || otherStatus) throw new BindingReplyParser.Invalid();
            // Implicit-presence boolean: a present, exactly empty value means off.
            if (!value.more()) return OFF;
            Long on = null;
            while (value.more()) {
                int tag = value.tag();
                if (tag != 8 || on != null) throw new BindingReplyParser.Invalid();
                on = value.scalar();
            }
            if (on > 1) throw new BindingReplyParser.Invalid();
            return on.intValue();
        } catch (BindingReplyParser.Invalid invalid) {
            return MALFORMED;
        }
    }

    private ScreenStatusParser() { }
}
