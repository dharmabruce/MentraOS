package com.mentra.bluetoothsdk.sgcs.inmo;

/**
 * Opaque binding identifier bytes: bounded, non-empty, not all zero. Never stringify or log.
 * Ported from lens-spark@79d33c2 (protocol/AccessIdentifier.java), originally imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/setup/AccessIdentifier.java
 */
public final class AccessIdentifier {
    public static final int MAX_LENGTH = 256;

    public static boolean valid(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_LENGTH) return false;
        for (byte b : value) if (b != 0) return true;
        return false;
    }

    private AccessIdentifier() { }
}
