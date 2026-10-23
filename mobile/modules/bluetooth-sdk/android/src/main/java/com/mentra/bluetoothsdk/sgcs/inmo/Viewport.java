package com.mentra.bluetoothsdk.sgcs.inmo;

/**
 * GO3 geometry: a 640×480 image of which only rows 60–478 are visible (the glasses' status bar is at the bottom).
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/mirror/SafeViewport.java
 * Ported from lens-spark@79d33c2 (image/Viewport.java).
 */
public final class Viewport {
    public static final int WIDTH = 640, HEIGHT = 480, TOP = 60;
    /** Rows 60–479 hold content (the spec's visible rows 60–478, plus the last row). */
    public static final int CONTENT_HEIGHT = HEIGHT - TOP;

    /** Fits a source of the given size into the visible area, centered, keeping aspect ratio.
     *  {@code bottomReserve} keeps a strip at the bottom free. */
    public static int[] fit(int width, int height, int bottomReserve) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image size");
        int available = CONTENT_HEIGHT - Math.max(0, Math.min(100, bottomReserve));
        double scale = Math.min((double) WIDTH / width, (double) available / height);
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        return new int[]{(WIDTH - w) / 2, TOP + (available - h) / 2, w, h};
    }

    private Viewport() { }
}
