package com.mentra.bluetoothsdk.sgcs.inmo;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Encodes opaque pixels as 2/4-bit indexed grayscale or 8-bit grayscale PNG, choosing the smaller of
 * unfiltered and adaptive Sub/Up filtering.
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/png/IndexedPngEncoder.java
 * Ported from lens-spark@79d33c2 (image/PngEncoder.java).
 */
public final class PngEncoder {
    private static final byte[] SIGNATURE = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    private PngEncoder() { }

    public static byte[] encode(Bitmap bitmap, Quality quality) throws IOException {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        try { return encode(bitmap.getWidth(), bitmap.getHeight(), pixels, quality); }
        finally { Arrays.fill(pixels, 0); }
    }

    static byte[] encode(int width, int height, int[] argb, Quality quality) throws IOException {
        byte[] baseline=encodeVariant(width,height,argb,quality,false);
        byte[] filtered=encodeVariant(width,height,argb,quality,true);
        // Choose by actual PNG size, including framing/chunks. Never enlarge the baseline.
        byte[] result=smaller(baseline,filtered);
        if(quality.bitDepth==8) {
            // tools/png-bench (synthetic screens, 2026-10-15): for 8-bit gray, all five row filters with
            // zlib FILTERED are ~10% smaller (12-14% on photos/video). For 2/4-bit indexed, unfiltered
            // rows already won on every screen, so those stay as they were.
            result=smaller(result,encodeWith(width,height,argb,quality,ALL,Deflater.FILTERED,Deflater.BEST_COMPRESSION));
        }
        return result;
    }

    /** Returns the shorter PNG and wipes the other. */
    private static byte[] smaller(byte[] a, byte[] b) {
        byte[] keep=b.length<a.length?b:a;
        Arrays.fill(keep==a?b:a,(byte)0);
        return keep;
    }

    // PNG row filters, as a bit mask for encodeWith().
    static final int NONE = 1, SUB = 2, UP = 4, AVERAGE = 8, PAETH = 16, ALL = 31;

    static byte[] encodeVariant(int width,int height,int[] argb,Quality quality,boolean adaptive) throws IOException {
        return encodeWith(width,height,argb,quality,adaptive?NONE|SUB|UP:NONE,Deflater.DEFAULT_STRATEGY,Deflater.BEST_COMPRESSION);
    }

    /**
     * Encodes with the given row filters (each row uses the enabled filter with the smallest sum of
     * absolute filtered bytes, the usual PNG heuristic) and zlib strategy/level. Bytes per pixel is 1
     * for every LensSpark format, so "left" is the previous byte.
     */
    static byte[] encodeWith(int width,int height,int[] argb,Quality quality,int filters,int strategy,int level) throws IOException {
        int depth=quality.bitDepth, levels=(1<<depth)-1;
        boolean indexed=depth<8;
        if(width<=0 || height<=0 || argb.length!=width*height)
            throw new IllegalArgumentException("Pixel dimensions do not match");
        if((filters&ALL)==0) throw new IllegalArgumentException("No row filter enabled");
        ByteArrayOutputStream result=new ByteArrayOutputStream(); result.write(SIGNATURE);
        ByteArrayOutputStream headerBytes=new ByteArrayOutputStream(13);
        DataOutputStream header=new DataOutputStream(headerBytes);
        header.writeInt(width); header.writeInt(height); header.writeByte(depth);
        header.writeByte(indexed?3:0); header.writeByte(0); header.writeByte(0); header.writeByte(0);
        writeChunk(result,"IHDR",headerBytes.toByteArray());
        if(indexed) {
            byte[] palette=new byte[(levels+1)*3];
            for(int i=0;i<=levels;i++) Arrays.fill(palette,i*3,i*3+3,(byte)(i*255/levels));
            writeChunk(result,"PLTE",palette);
        }
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        Deflater deflater=new Deflater(level);
        deflater.setStrategy(strategy);
        int rowBytes=(width*depth+7)/8;
        byte[] previous=new byte[rowBytes],current=new byte[rowBytes];
        byte[][] candidates=new byte[5][rowBytes+1];
        try(DeflaterOutputStream rows=new DeflaterOutputStream(compressed,deflater)) {
            for(int y=0;y<height;y++) {
                Arrays.fill(current,(byte)0);
                for(int x=0;x<width;x++) {
                    int color=argb[y*width+x];
                    int luma=(((color>>16)&255)*54+((color>>8)&255)*183+(color&255)*19)>>8;
                    int sample=(luma*levels+127)/255;
                    current[x*depth/8]|=(byte)(sample<<(8-depth-(x*depth%8)));
                }
                byte[] chosen=null; long best=Long.MAX_VALUE;
                for(int type=0;type<5;type++) {
                    if((filters&(1<<type))==0) continue;
                    byte[] row=candidates[type]; row[0]=(byte)type; long score=0;
                    for(int i=0;i<rowBytes;i++) {
                        int x=current[i]&255, a=i==0?0:current[i-1]&255, b=previous[i]&255, c=i==0?0:previous[i-1]&255;
                        int predictor;
                        switch(type) {
                            case 1: predictor=a; break;
                            case 2: predictor=b; break;
                            case 3: predictor=(a+b)>>1; break;
                            case 4: predictor=paeth(a,b,c); break;
                            default: predictor=0;
                        }
                        row[i+1]=(byte)(x-predictor);
                        score+=Math.abs((int)row[i+1]);
                    }
                    if(score<best) { best=score; chosen=row; }
                }
                rows.write(chosen);
                byte[] swap=previous; previous=current; current=swap;
            }
        } finally {
            Arrays.fill(previous,(byte)0); Arrays.fill(current,(byte)0);
            for(byte[] buffer:candidates) Arrays.fill(buffer,(byte)0);
            deflater.end();
        }
        writeChunk(result,"IDAT",compressed.toByteArray()); writeChunk(result,"IEND",new byte[0]);
        return result.toByteArray();
    }

    private static int paeth(int a,int b,int c) {
        int p=a+b-c, pa=Math.abs(p-a), pb=Math.abs(p-b), pc=Math.abs(p-c);
        return pa<=pb&&pa<=pc?a:pb<=pc?b:c;
    }

    private static void writeChunk(ByteArrayOutputStream output, String name, byte[] value)
            throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(value.length);
        data.write(type);
        data.write(value);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(value);
        data.writeInt((int) crc.getValue());
    }
}

