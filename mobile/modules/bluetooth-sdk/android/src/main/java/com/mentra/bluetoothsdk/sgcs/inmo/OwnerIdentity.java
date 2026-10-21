package com.mentra.bluetoothsdk.sgcs.inmo;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * This client's own RFCOMM binding identity: six characters from A–Z and 2–7. Six characters avoids
 * firmware truncation. A local association marker, not a vendor account or secret.
 * Ported from lens-spark@79d33c2 (protocol/OwnerIdentity.java), originally imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/setup/FreshOwnerIdentity.java
 */
public final class OwnerIdentity {
    private static final byte[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".getBytes(StandardCharsets.US_ASCII);

    public static byte[] generate() {
        SecureRandom random = new SecureRandom();
        byte[] value = new byte[6];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return value;
    }

    public static boolean valid(byte[] value) {
        if (value == null || value.length != 6) return false;
        for (byte b : value) if (!((b >= 'A' && b <= 'Z') || (b >= '2' && b <= '7'))) return false;
        return true;
    }

    private OwnerIdentity() { }
}
