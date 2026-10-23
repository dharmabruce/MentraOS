package com.mentra.bluetoothsdk.sgcs.inmo;

/**
 * GO3 image depth: Fast = 4 grays, Balanced = 16, High = 256, Text = 2 tones.
 * Ported from lens-spark@79d33c2 (image/Quality.java).
 */
public enum Quality {
    FAST(2), BALANCED(4), HIGH(8), TEXT(1);

    public final int bitDepth;

    Quality(int bitDepth) {
        this.bitDepth = bitDepth;
    }

    public static Quality parse(String value) {
        for (Quality quality : values()) if (quality.name().equals(value)) return quality;
        return TEXT;  // default: e-book mode (decided 2026-10-16)
    }
}
