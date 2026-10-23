package com.mentra.bluetoothsdk.sgcs.inmo;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public final class PngEncoderTest {
    @Test public void emitsExpectedGeometryAndIndexedTwoBitHeader() throws Exception {
        int width = 640;
        int height = 480;
        int[] pixels = new int[width * height];
        pixels[60 * width] = 0xff92f7a5;
        byte[] png = PngEncoder.encode(width, height, pixels, Quality.FAST);
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10},
                java.util.Arrays.copyOf(png, 8));
        ByteBuffer data = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        assertEquals(width, data.getInt(16));
        assertEquals(height, data.getInt(20));
        assertEquals(2, png[24]);
        assertEquals(3, png[25]);
        assertTrue(indexOf(png, new byte[]{'P', 'L', 'T', 'E'}) > 0);
    }

    @Test public void fastDecodesToExactlyFourNeutralLevelsIncludingOddWidthPadding() throws Exception {
        int[] pixels=new int[257*3];
        for(int y=0;y<3;y++) for(int x=0;x<257;x++) {
            int gray=Math.min(255,x); pixels[y*257+x]=0xff000000|gray*0x010101;
        }
        byte[] png=PngEncoder.encode(257,3,pixels,Quality.FAST);
        Object decoded=decode(png);
        java.util.Set<Integer> levels=new java.util.HashSet<>();
        for(int y=0;y<3;y++) for(int x=0;x<257;x++) {
            int rgb=rgb(decoded,x,y), r=(rgb>>16)&255, g=(rgb>>8)&255, b=rgb&255;
            assertEquals(r,g); assertEquals(g,b); levels.add(r);
            assertTrue(Math.abs(r-Math.min(255,x))<=42);
        }
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(0,85,170,255)),levels);
        assertEquals(2,png[24]); assertEquals(3,png[25]);
    }
    @Test public void highPreservesEveryEightBitGraySampleWithoutPalette() throws Exception {
        int[] pixels=new int[256];
        for(int x=0;x<256;x++) pixels[x]=0xff000000|x*0x010101;
        byte[] png=PngEncoder.encode(256,1,pixels,Quality.HIGH);
        assertEquals(8,png[24]); assertEquals(0,png[25]);
        Object decoded=decode(png);
        for(int x=0;x<256;x++) assertEquals(x,sample(decoded,x));
        assertEquals(-1,indexOf(png,new byte[]{'P','L','T','E'}));
    }
    @Test public void colorUsesLuminanceInsteadOfOneChannel() throws Exception {
        int[] colors={0xffff0000,0xff00ff00,0xff0000ff};
        byte[] png=PngEncoder.encode(3,1,colors,Quality.HIGH);
        Object image=decode(png);
        assertEquals(53,sample(image,0));
        assertEquals(182,sample(image,1));
        assertEquals(18,sample(image,2));
    }

    @Test public void balancedHasTrueFourBitDepthAndSixteenNeutralLevels() throws Exception {
        int[] pixels=new int[257*4];
        for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000|Math.min(255,i%257)*0x010101;
        byte[] png=PngEncoder.encode(257,4,pixels,Quality.BALANCED);
        assertEquals(4,png[24]); assertEquals(3,png[25]);
        Object image=decode(png); java.util.Set<Integer> levels=new java.util.HashSet<>();
        for(int y=0;y<4;y++) for(int x=0;x<257;x++) {
            int value=rgb(image,x,y)&255; levels.add(value);
            assertEquals(0,value%17); assertTrue(Math.abs(value-Math.min(255,x))<=8);
        }
        assertEquals(16,levels.size());
    }
    @Test public void filteringIsPixelEquivalentAndNeverLargerIncludingOddWidths() throws Exception {
        java.util.Random random=new java.util.Random(73);
        for(Quality mode:Quality.values()) for(int width:new int[]{1,17,257}) {
            int height=65; int[] pixels=new int[width*height];
            for(int i=0;i<pixels.length;i++) pixels[i]=i<width*60?0xff000000:random.nextInt()|0xff000000;
            byte[] baseline=PngEncoder.encodeVariant(width,height,pixels,mode,false);
            byte[] candidate=PngEncoder.encodeVariant(width,height,pixels,mode,true);
            byte[] chosen=PngEncoder.encode(width,height,pixels,mode);
            assertTrue(chosen.length<=baseline.length);
            Object a=decode(baseline), b=decode(candidate), c=decode(chosen);
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                assertEquals(rgb(a,x,y),rgb(b,x,y)); assertEquals(rgb(a,x,y),rgb(c,x,y));
                if(y<60) assertEquals(0,rgb(c,x,y)&0xffffff);
            }
        }
    }
    @Test public void everyFilterAndStrategyDecodesToTheSamePixels() throws Exception {
        java.util.Random random=new java.util.Random(11);
        int width=97, height=70; int[] pixels=new int[width*height];
        for(int i=0;i<pixels.length;i++) pixels[i]=(i/width)%7==0?random.nextInt()|0xff000000:0xff000000|((i*37)%256)*0x010101;
        for(Quality mode:Quality.values()) {
            Object reference=decode(PngEncoder.encodeVariant(width,height,pixels,mode,false));
            for(int filters:new int[]{PngEncoder.NONE,PngEncoder.SUB,PngEncoder.UP,PngEncoder.AVERAGE,PngEncoder.PAETH,PngEncoder.ALL})
                for(int strategy:new int[]{java.util.zip.Deflater.DEFAULT_STRATEGY,java.util.zip.Deflater.FILTERED}) {
                    Object candidate=decode(PngEncoder.encodeWith(width,height,pixels,mode,filters,strategy,9));
                    for(int y=0;y<height;y++) for(int x=0;x<width;x++)
                        assertEquals(mode+" filters="+filters,rgb(reference,x,y),rgb(candidate,x,y));
                }
        }
    }

    @Test public void chosenEncodingIsNeverLargerThanTheOldOne() throws Exception {
        java.util.Random random=new java.util.Random(5);
        int[] pixels=new int[640*480];
        for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000|(((i%640)/3+(i/640)/2+random.nextInt(9))&255)*0x010101;
        for(Quality mode:Quality.values()) {
            byte[] old=PngEncoder.encodeVariant(640,480,pixels,mode,false), oldFiltered=PngEncoder.encodeVariant(640,480,pixels,mode,true);
            assertTrue(mode.name(),PngEncoder.encode(640,480,pixels,mode).length<=Math.min(old.length,oldFiltered.length));
        }
    }

    @Test public void savedQualityNamesRoundTripAndDefaultToText() {
        assertEquals(Quality.TEXT,Quality.parse(null));
        assertEquals(Quality.TEXT,Quality.parse("garbage"));
        for(Quality mode:Quality.values()) assertEquals(mode,Quality.parse(mode.name()));
    }

    // Android's compile bootclasspath omits desktop ImageIO, while the host JVM provides it.
    // Reflection keeps the independent decoder in both Gradle JVM and direct JVM tests.
    private static Object decode(byte[] png) throws Exception {
        return Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream.class)
                .invoke(null,new java.io.ByteArrayInputStream(png));
    }
    private static int rgb(Object image,int x,int y) throws Exception {
        return (Integer)Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",int.class,int.class).invoke(image,x,y);
    }
    private static int sample(Object image,int x) throws Exception {
        Object raster=Class.forName("java.awt.image.BufferedImage").getMethod("getRaster").invoke(image);
        return (Integer)Class.forName("java.awt.image.Raster").getMethod("getSample",int.class,int.class,int.class).invoke(raster,x,0,0);
    }

    private static int indexOf(byte[] value, byte[] needle) {
        outer: for (int i = 0; i <= value.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (value[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}

