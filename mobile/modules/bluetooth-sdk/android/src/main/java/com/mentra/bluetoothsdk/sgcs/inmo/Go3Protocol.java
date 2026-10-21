package com.mentra.bluetoothsdk.sgcs.inmo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Measured INMO GO3 AA55 framing and messages; images display through the Navigation app
 * (openNavigation → destinationArrived(png) → openNavigation). Protocol notes:
 * github.com/dharmabruce/lens-spark docs/PROTOCOL.md.
 * Ported from lens-spark@79d33c2:app/src/main/java/com/dharmabruce/lensspark/protocol/Go3Protocol.java,
 * originally imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/protocol/Go3Protocol.java
 * (brightness, screenshot, wake and diagnostics observers removed).
 */
public final class Go3Protocol {
    static final int MAX_FRAGMENT = 230;
    private int sequence = 0x7000;

    /** GATT only: enables the protocol bridge on the BR/EDR GATT channel. Not a registration write. */
    public void enableGattOverBredr(OutputStream out) throws IOException {
        send(out, bytes(0x10, 0x14, 0xa2, 0x01, 0x02, 0x50, 0x00));
    }

    /** Operation 1: authenticate with an existing binding identifier. */
    public void login(OutputStream out, byte[] identifier) throws IOException {
        if (!AccessIdentifier.valid(identifier)) throw new IOException("GO3 access is unavailable");
        sendBindingOperation(out, 0x01, identifier);
    }

    /** Operation 2: persistent local binding. Caller must have confirmed UNBOUND and saved the identity first. */
    public void bindOwner(OutputStream out, byte[] ownerIdentity) throws IOException {
        if (!OwnerIdentity.valid(ownerIdentity)) throw new IOException("Owner binding identity is unavailable");
        sendBindingOperation(out, 0x02, ownerIdentity);
    }

    /** Operation 6: persistent release. Caller must have proven the binding is this client's own. */
    public void releaseOwner(OutputStream out) throws IOException {
        send(out, bytes(0x10, 0x18, 0xda, 0x01, 0x02, 0x08, 0x06));
    }

    /** Operation 0: read-only binding query. */
    public void queryBindState(OutputStream out) throws IOException {
        send(out, bytes(0x10, 0x18, 0xda, 0x01, 0x02, 0x08, 0x00));
    }

    public void advertiseNavigation(OutputStream out) throws IOException {
        send(out, bytes(0x10, 0x23, 0xb2, 0x02, 0x03, 0x0a, 0x01, 0x02));
    }

    public void openNavigation(OutputStream out) throws IOException {
        send(out, bytes(0x10, 0x0f, 0x92, 0x01, 0x02, 0x08, 0x0d));
    }

    public void destinationArrived(OutputStream out, byte[] png) throws IOException {
        byte[] arrived = cat(bytes(0x08), varint(1864), bytes(0x10), varint(418),
                bytes(0x1a, 0x03, 'U', 'S', 'A', 0x22), varint(png.length), png);
        byte[] navigation = cat(bytes(0x08, 0x0a, 0x62), varint(arrived.length), arrived);
        send(out, cat(bytes(0x10, 0x21, 0xa2, 0x02), varint(navigation.length), navigation));
    }

    private void sendBindingOperation(OutputStream out, int operation, byte[] value) throws IOException {
        byte[] inner = cat(bytes(0x08, operation, 0x12), varint(value.length), value);
        byte[] message = cat(bytes(0x10, 0x18, 0xda, 0x01), varint(inner.length), inner);
        try {
            send(out, message);
        } finally {
            Arrays.fill(inner, (byte) 0);
            Arrays.fill(message, (byte) 0);
        }
    }

    /** Writes one AA55 frame per {@code out.write} call; GATT maps each write to one characteristic write. */
    synchronized void send(OutputStream out, byte[] payload) throws IOException {
        int fragments = Math.max(1, (payload.length + MAX_FRAGMENT - 1) / MAX_FRAGMENT);
        int current = sequence++ & 0xffff;
        for (int index = 0; index < fragments; index++) {
            int offset = index * MAX_FRAGMENT;
            int length = Math.min(MAX_FRAGMENT, payload.length - offset);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0xaa);
            frame.write(0x55);
            u16(frame, current);
            u16(frame, fragments);
            u16(frame, index);
            frame.write(0x03);
            frame.write(0x01);
            u16(frame, length);
            frame.write(payload, offset, length);
            u16(frame, crc16(frame.toByteArray()));
            out.write(frame.toByteArray());
        }
        out.flush();
    }

    /** CRC-16/CCITT-FALSE. */
    static int crc16(byte[] data) {
        return crc16(data, data.length);
    }

    static int crc16(byte[] data, int length) {
        int crc = 0xffff;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++)
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
        }
        return crc;
    }

    static byte[] varint(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        return out.toByteArray();
    }

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }
}
