package com.mentra.bluetoothsdk.sgcs.inmo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class Go3ProtocolTest {
    @Test public void crcMatchesCcittFalseCheckValue() {
        assertEquals(0x29b1, Go3Protocol.crc16("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test public void varintCrossesOneByteBoundary() {
        assertArrayEquals(new byte[]{0x7f}, Go3Protocol.varint(127));
        assertArrayEquals(new byte[]{(byte) 0x80, 0x01}, Go3Protocol.varint(128));
    }

    @Test public void frameHasMeasuredHeaderLengthAndCrc() throws Exception {
        Go3Protocol protocol = new Go3Protocol();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        protocol.send(output, new byte[]{1, 2, 3});
        byte[] frame = output.toByteArray();
        assertEquals(17, frame.length);
        assertEquals((byte) 0xaa, frame[0]);
        assertEquals(0x55, frame[1]);
        assertEquals(1, frame[5]);
        assertEquals(0, frame[7]);
        assertEquals(3, frame[11]);
        int actualCrc = ((frame[frame.length - 2] & 0xff) << 8) | (frame[frame.length - 1] & 0xff);
        assertEquals(Go3Protocol.crc16(Arrays.copyOf(frame, frame.length - 2)), actualCrc);
    }

    @Test public void payloadIsFragmentedAt230Bytes() throws Exception {
        Go3Protocol protocol = new Go3Protocol();
        CountingOutputStream output = new CountingOutputStream();
        protocol.send(output, new byte[231]);
        assertEquals(2, output.writes);
        assertEquals(244, output.lengths[0]);
        assertEquals(15, output.lengths[1]);
    }

    @Test public void loginUsesDynamicOpaqueByteLengths() throws Exception {
        for(int length:new int[]{1,8,127,128,256}) {
            byte[] id=new byte[length];Arrays.fill(id,(byte)0xff);ByteArrayOutputStream wire=new ByteArrayOutputStream();new Go3Protocol().login(wire,id);
            ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] frames=wire.toByteArray();
            for(int offset=0;offset<frames.length;){int count=((frames[offset+10]&255)<<8)|(frames[offset+11]&255);body.write(frames,offset+12,count);offset+=count+14;}
            byte[] inner=Go3Protocol.cat(new byte[]{8,1,18},Go3Protocol.varint(length),id);
            assertArrayEquals(Go3Protocol.cat(new byte[]{16,24,(byte)218,1},Go3Protocol.varint(inner.length),inner),body.toByteArray());assertEquals((byte)255,id[0]);
        }
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[257],new byte[]{0}})try{new Go3Protocol().login(new ByteArrayOutputStream(),invalid);fail();}catch(java.io.IOException expected){}
    }

    @Test public void releaseUsesOnlyOperationSixWithoutBindingOrIdentity()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();new Go3Protocol().releaseOwner(out);byte[] frame=out.toByteArray();
        assertArrayEquals(new byte[]{0x10,0x18,(byte)0xda,1,2,8,6},Arrays.copyOfRange(frame,12,frame.length-2));
        assertEquals(21,frame.length);assertEquals(Go3Protocol.crc16(Arrays.copyOf(frame,frame.length-2)),((frame[19]&255)<<8)|(frame[20]&255));
    }
    @Test public void destinationArrivedWrapsPngInNavigationMessage() throws Exception {
        byte[] png = new byte[500];
        Arrays.fill(png, (byte) 0x5a);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new Go3Protocol().destinationArrived(wire, png);
        byte[] arrived = Go3Protocol.cat(new byte[]{0x08}, Go3Protocol.varint(1864), new byte[]{0x10}, Go3Protocol.varint(418),
                new byte[]{0x1a, 0x03, 'U', 'S', 'A', 0x22}, Go3Protocol.varint(png.length), png);
        byte[] navigation = Go3Protocol.cat(new byte[]{0x08, 0x0a, 0x62}, Go3Protocol.varint(arrived.length), arrived);
        byte[] expected = Go3Protocol.cat(new byte[]{0x10, 0x21, (byte) 0xa2, 0x02}, Go3Protocol.varint(navigation.length), navigation);
        assertArrayEquals(expected, reassemble(wire.toByteArray()));
    }

    @Test public void openNavigationIsMeasuredMessage() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new Go3Protocol().openNavigation(wire);
        assertArrayEquals(new byte[]{0x10, 0x0f, (byte) 0x92, 0x01, 0x02, 0x08, 0x0d}, reassemble(wire.toByteArray()));
    }

    private static byte[] reassemble(byte[] frames) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int offset = 0; offset < frames.length; ) {
            int count = ((frames[offset + 10] & 255) << 8) | (frames[offset + 11] & 255);
            body.write(frames, offset + 12, count);
            offset += count + 14;
        }
        return body.toByteArray();
    }

    private static final class CountingOutputStream extends java.io.OutputStream {
        int writes;
        final int[] lengths = new int[4];
        @Override public void write(int value) { throw new UnsupportedOperationException(); }
        @Override public void write(byte[] value, int offset, int length) {
            lengths[writes++] = length;
        }
    }
}

