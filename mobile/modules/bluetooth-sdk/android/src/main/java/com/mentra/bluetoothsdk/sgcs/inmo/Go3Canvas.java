package com.mentra.bluetoothsdk.sgcs.inmo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Base64;

import java.io.IOException;

/**
 * Renders MentraOS display content as GO3 frames: a 640×480 PNG with content kept inside the
 * visible rows (below {@link Viewport#TOP}). The glasses show bright pixels as green on a clear
 * field, so everything is drawn bright on black.
 *
 * <p>Text is 18 px monospace on a 22 px line pitch: 55 columns by 18 lines. The engine's GO3 display
 * profile ({@code modules/engine/src/utils/display/profiles/go3.ts}) wraps text for exactly these
 * metrics, so keep the two in step.
 */
public final class Go3Canvas {
    static final int SIDE_MARGIN = 16;
    static final int TOP_MARGIN = 12;
    static final float TEXT_SIZE = 18f;
    static final int LINE_HEIGHT = 22;

    private Go3Canvas() { }

    /** A blank frame: nothing lit. */
    public static byte[] blank() throws IOException {
        Bitmap frame = newFrame();
        try {
            return PngEncoder.encode(frame, Quality.TEXT);
        } finally {
            frame.recycle();
        }
    }

    /** One block of wrapped text from the top of the visible area; overflow is cut with an ellipsis. */
    public static byte[] text(String text) throws IOException {
        Bitmap frame = newFrame();
        try {
            Canvas canvas = new Canvas(frame);
            drawText(canvas, text, Viewport.TOP + TOP_MARGIN, Viewport.HEIGHT - TOP_MARGIN);
            return PngEncoder.encode(frame, Quality.FAST);
        } finally {
            frame.recycle();
        }
    }

    /** Two blocks of text: {@code top} in the upper half of the visible area, {@code bottom} in the lower. */
    public static byte[] doubleText(String top, String bottom) throws IOException {
        Bitmap frame = newFrame();
        try {
            Canvas canvas = new Canvas(frame);
            int middle = Viewport.TOP + Viewport.CONTENT_HEIGHT / 2;
            drawText(canvas, top, Viewport.TOP + TOP_MARGIN, middle - TOP_MARGIN / 2);
            drawText(canvas, bottom, middle + TOP_MARGIN / 2, Viewport.HEIGHT - TOP_MARGIN);
            return PngEncoder.encode(frame, Quality.FAST);
        } finally {
            frame.recycle();
        }
    }

    /** A base64-encoded image (any format Android decodes), fitted into the visible area. */
    public static byte[] image(String base64) throws IOException {
        byte[] data;
        try {
            data = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Image data isn't valid base64", invalid);
        }
        Bitmap source = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (source == null) throw new IOException("Image data couldn't be decoded");
        Bitmap frame = newFrame();
        try {
            int[] fit = Viewport.fit(source.getWidth(), source.getHeight(), 0);
            Canvas canvas = new Canvas(frame);
            canvas.drawBitmap(source, null, new Rect(fit[0], fit[1], fit[0] + fit[2], fit[1] + fit[3]),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            return PngEncoder.encode(frame, Quality.BALANCED);
        } finally {
            source.recycle();
            frame.recycle();
        }
    }

    private static Bitmap newFrame() {
        Bitmap frame = Bitmap.createBitmap(Viewport.WIDTH, Viewport.HEIGHT, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.BLACK);
        return frame;
    }

    private static void drawText(Canvas canvas, String text, int top, int bottom) {
        if (text == null || text.isEmpty() || bottom <= top) return;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        // Monospace has no bold face on Android; fake bold keeps strokes legible without changing advances.
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setFakeBoldText(true);
        paint.setTextSize(TEXT_SIZE);
        int width = Viewport.WIDTH - 2 * SIDE_MARGIN;
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_HEIGHT - paint.getFontMetricsInt(null), 1f)
                .setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setMaxLines(Math.max(1, (bottom - top) / LINE_HEIGHT))
                .build();
        canvas.save();
        canvas.clipRect(SIDE_MARGIN, top, SIDE_MARGIN + width, bottom);
        canvas.translate(SIDE_MARGIN, top);
        layout.draw(canvas);
        canvas.restore();
    }
}
