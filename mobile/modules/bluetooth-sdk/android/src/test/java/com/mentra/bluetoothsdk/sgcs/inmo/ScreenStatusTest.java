package com.mentra.bluetoothsdk.sgcs.inmo;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.io.*;
public final class ScreenStatusTest {
    private static byte[] message(int state){return new byte[]{16,20,(byte)186,1,6,8,10,90,2,8,(byte)state};}
    private static byte[] frame(int source,int seq,int count,int index,byte[] body){byte[] b=new byte[body.length+14];b[0]=(byte)170;b[1]=85;u16(b,2,seq);u16(b,4,count);u16(b,6,index);b[8]=(byte)source;b[9]=3;u16(b,10,body.length);System.arraycopy(body,0,b,12,body.length);int crc=65535;for(int i=0;i<b.length-2;i++){crc^=(b[i]&255)<<8;for(int j=0;j<8;j++)crc=((crc&32768)!=0?(crc<<1)^0x1021:crc<<1)&65535;}u16(b,b.length-2,crc);return b;}
    private static void u16(byte[] b,int i,int n){b[i]=(byte)(n>>8);b[i+1]=(byte)n;}
    @Test public void explicitOrExactlyEmptyTypedBoolAndDifferentOperationsNeverMeanSleep(){assertEquals(0,ScreenStatusParser.parse(message(0)));assertEquals(1,ScreenStatusParser.parse(message(1)));assertEquals(-2,ScreenStatusParser.parse(message(2)));assertEquals(0,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,4,8,10,90,0}));byte[] b=message(0);b[6]=6;assertEquals(-1,ScreenStatusParser.parse(b));assertEquals(-1,ScreenStatusParser.parse(new byte[]{16,24}));}
    @Test public void duplicateTruncatedOversizedAndMixedSubjectsCannotStopSharing(){assertEquals(-2,ScreenStatusParser.parse(new byte[4097]));assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,(byte)128}));byte[] b=Arrays.copyOf(message(0),13);b[4]=8;b[8]=4;b[11]=8;b[12]=0;assertEquals(-2,ScreenStatusParser.parse(b));b=Arrays.copyOf(message(0),13);b[11]=16;b[12]=20;assertEquals(-2,ScreenStatusParser.parse(b));}
    @Test public void fragmentedValidFramesFromBothDocumentedEndpointsAreObserved(){for(int source:new int[]{1,2}){List<Integer> observed=new ArrayList<>();try(ScreenStatusDecoder d=new ScreenStatusDecoder(observed::add)){byte[] b=message(0);for(byte[] f:new byte[][]{frame(source,7,2,0,Arrays.copyOfRange(b,0,5)),frame(source,7,2,1,Arrays.copyOfRange(b,5,b.length))})for(byte n:f)d.accept(new byte[]{n},1);assertEquals(Arrays.asList(0),observed);}}}
    @Test public void crcUnknownSubjectAndReorderingCannotManufactureOff(){List<Integer> seen=new ArrayList<>();try(ScreenStatusDecoder d=new ScreenStatusDecoder(seen::add)){byte[] bad=frame(1,1,1,0,message(0));bad[bad.length-1]^=1;d.accept(bad,bad.length);bad=frame(3,2,1,0,message(0));d.accept(bad,bad.length);bad=frame(1,3,2,1,message(0));d.accept(bad,bad.length);assertTrue(seen.isEmpty());byte[] valid=frame(1,4,1,0,message(1));d.accept(valid,valid.length);assertEquals(Arrays.asList(1),seen);}}
    @Test public void fragmentsFromDifferentEndpointsCannotCombineIntoSleepProof(){List<Integer> seen=new ArrayList<>();try(ScreenStatusDecoder d=new ScreenStatusDecoder(seen::add)){byte[] b=message(0),a=frame(1,1,2,0,Arrays.copyOfRange(b,0,5)),z=frame(2,1,2,1,Arrays.copyOfRange(b,5,b.length));d.accept(a,a.length);d.accept(z,z.length);assertTrue(seen.isEmpty());}}
    @Test public void badTrafficDoesNotPreventLaterValidScreenReport(){List<Integer> seen=new ArrayList<>();try(ScreenStatusDecoder d=new ScreenStatusDecoder(seen::add)){byte[] garbage=new byte[5000];d.accept(garbage,garbage.length);byte[] b=frame(1,5,1,0,message(0));d.accept(b,b.length);assertEquals(Arrays.asList(0),seen);}}

    /** Source-backed: vendor DeviceStatusProto.DeviceScreenStatus (1669+) stores a plain
     * boolean, clears it false, and uses proto3 implicit-presence message metadata. */
    @Test public void emptyTypedFalseRequiresFullStrictScreenEnvelope(){
        byte[] empty={16,20,(byte)186,1,4,8,10,90,0};assertEquals(0,ScreenStatusParser.parse(empty));
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,2,8,10})); // absent screen value
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,4,8,10,88,0})); // wrong wire
        assertEquals(-1,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,4,8,9,90,0})); // wrong operation
        assertEquals(-1,ScreenStatusParser.parse(new byte[]{16,24,(byte)186,1,4,8,10,90,0})); // wrong type
        assertEquals(-1,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,2,90,0})); // absent operation
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,6,8,10,90,0,90,0})); // duplicate empty value
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,6,8,10,90,0,96,0})); // mixed status field
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,6,8,10,90,2,16,0})); // nonempty unknown value
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,4,8,10,90,0,24,0})); // mixed root
        assertEquals(-2,ScreenStatusParser.parse(new byte[]{16,20,(byte)186,1,5,8,10,90,(byte)128,0})); // noncanonical empty length
    }
    @Test public void emptyTypedFalseStillNeedsValidRoutedFrame(){
        byte[] empty={16,20,(byte)186,1,4,8,10,90,0};List<Integer> seen=new ArrayList<>();
        try(ScreenStatusDecoder decoder=new ScreenStatusDecoder(seen::add)){
            byte[] bad=frame(1,4,1,0,empty);bad[bad.length-1]^=1;decoder.accept(bad,bad.length);
            byte[] wrong=frame(3,5,1,0,empty);decoder.accept(wrong,wrong.length);assertTrue(seen.isEmpty());
            byte[] good=frame(2,6,1,0,empty);decoder.accept(good,good.length);assertEquals(Arrays.asList(0),seen);
        }
    }
    @Test public void unrelatedOtherEndpointDoesNotDiscardPendingScreenFragment(){
        List<Integer> seen=new ArrayList<>();byte[] off=message(0);
        try(ScreenStatusDecoder decoder=new ScreenStatusDecoder(seen::add)){
            for(byte[] bytes:new byte[][]{frame(1,9,2,0,Arrays.copyOfRange(off,0,5)),frame(2,9,1,0,new byte[]{16,24}),frame(1,9,2,1,Arrays.copyOfRange(off,5,off.length))})decoder.accept(bytes,bytes.length);
            assertEquals(Arrays.asList(0),seen);
        }
    }
    @Test public void bothEndpointsCanAssembleSameSequenceIndependently(){
        List<Integer> seen=new ArrayList<>();byte[] off=message(0),on=message(1);
        try(ScreenStatusDecoder decoder=new ScreenStatusDecoder(seen::add)){
            for(byte[] bytes:new byte[][]{frame(1,12,2,0,Arrays.copyOfRange(off,0,5)),frame(2,12,2,0,Arrays.copyOfRange(on,0,5)),frame(2,12,2,1,Arrays.copyOfRange(on,5,on.length)),frame(1,12,2,1,Arrays.copyOfRange(off,5,off.length))})
                for(byte value:bytes)decoder.accept(new byte[]{value},1);
            assertEquals(Arrays.asList(1,0),seen);
        }
    }
    @Test public void wrongSourceTailCannotJoinAndLaterIndependentProofWorks(){
        List<Integer> seen=new ArrayList<>();byte[] off=message(0);
        try(ScreenStatusDecoder decoder=new ScreenStatusDecoder(seen::add)){
            byte[] first=frame(1,14,2,0,Arrays.copyOfRange(off,0,5)),wrong=frame(3,14,2,1,Arrays.copyOfRange(off,5,off.length));decoder.accept(first,first.length);decoder.accept(wrong,wrong.length);assertTrue(seen.isEmpty());
            byte[] independent=frame(2,15,1,0,message(1));decoder.accept(independent,independent.length);assertEquals(Arrays.asList(1),seen);
        }
    }
}
