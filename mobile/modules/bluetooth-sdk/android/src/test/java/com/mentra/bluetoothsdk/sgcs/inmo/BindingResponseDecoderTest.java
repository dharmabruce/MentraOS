package com.mentra.bluetoothsdk.sgcs.inmo;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.mentra.bluetoothsdk.sgcs.inmo.Fixtures.*;
public final class BindingResponseDecoderTest {
    @Test public void fragmentedReadsAndMessageReassembly(){byte[] body=found(new byte[]{71,72,73});byte[] wire=cat(frame(19,2,0,Arrays.copyOfRange(body,0,5)),frame(19,2,1,Arrays.copyOfRange(body,5,body.length)));
        try(BindingResponseDecoder decoder=new BindingResponseDecoder()){for(byte b:wire)decoder.accept(new byte[]{b},1);try(BindingReply reply=decoder.takeReply()){assertEquals(BindingReply.Kind.ACCESS_FOUND,reply.kind);assertArrayEquals(new byte[]{71,72,73},reply.copyIdentifier());}}}
    @Test public void unrelatedAndEchoThenValidResponse(){byte[] wire=cat(frame(3,1,0,new byte[]{16,2}),frame(4,1,0,cat(new byte[]{16,24},field(218,new byte[]{8,0}))),frame(5,1,0,found(new byte[]{1})));check(wire,BindingReply.Kind.ACCESS_FOUND);}
    @Test public void rejectsCrcHeaderSubjectReorderRepeatedSequenceAndCount(){byte[] valid=frame(1,1,0,found(new byte[]{1}));byte[] bad=valid.clone();bad[bad.length-1]^=1;check(bad,BindingReply.Kind.MALFORMED);
        bad=valid.clone();bad[8]=3;check(bad,BindingReply.Kind.MALFORMED);
        check(frame(1,2,1,new byte[]{16}),BindingReply.Kind.MALFORMED);
        check(cat(frame(1,2,0,new byte[]{16}),frame(2,2,1,new byte[]{24})),BindingReply.Kind.MALFORMED);
        check(cat(frame(1,2,0,new byte[]{16}),frame(1,2,0,new byte[]{24})),BindingReply.Kind.MALFORMED);
        check(cat(frame(1,2,0,new byte[]{16}),frame(1,3,1,new byte[]{24})),BindingReply.Kind.MALFORMED);
        check(frame(1,33,0,new byte[]{16}),BindingReply.Kind.MALFORMED);
        bad=valid.clone();u16(bad,10,4097);check(bad,BindingReply.Kind.MALFORMED);
    }
    @Test public void capsGarbageAndAccumulatedMessage(){check(new byte[32769],BindingReply.Kind.LIMIT_EXCEEDED);
        check(cat(frame(1,2,0,new byte[4096]),frame(1,2,1,new byte[1])),BindingReply.Kind.MALFORMED);}
    @Test public void incompleteAndClosedDecoder(){BindingResponseDecoder decoder=new BindingResponseDecoder();byte[] wire=frame(1,1,0,found(new byte[]{1}));decoder.accept(wire,wire.length-1);assertNull(decoder.takeReply());decoder.close();try{decoder.accept(wire,wire.length);fail();}catch(IllegalStateException expected){}}
    @Test public void deviceStatusAndReconnectCanArriveBeforeBindingProof(){
        byte[] status=new byte[]{16,20,(byte)186,1,6,8,6,58,2,8,80};
        byte[] reconnect=cat(new byte[]{16,24},field(218,cat(new byte[]{8,5},field(50,new byte[]{8,1}))));
        byte[] wire=cat(frame(1,1,0,status),frame(2,1,0,reconnect),frame(3,1,0,found(new byte[]{65})));
        check(wire,BindingReply.Kind.ACCESS_FOUND);
    }
    private static byte[] routed(int source,int destination,int seq,int count,int index,byte[] body){
        byte[] wire=frame(seq,count,index,body);wire[8]=(byte)source;wire[9]=(byte)destination;u16(wire,wire.length-2,crc(wire));return wire;
    }
    @Test public void sourceTwoStatusBeforeSourceOneBindingIsIgnored(){
        check(cat(routed(2,3,1,1,0,cat(new byte[]{16,20},field(122,new byte[26]))),frame(2,1,0,found(new byte[]{65}))),BindingReply.Kind.ACCESS_FOUND);
    }
    @Test public void completeSourceTwoTypedBindingStatusIsAccepted(){
        check(routed(2,3,1,1,0,found(new byte[]{65})),BindingReply.Kind.ACCESS_FOUND);
        check(routed(2,3,1,1,0,response(new byte[]{8,0})),BindingReply.Kind.UNBOUND);
        check(routed(2,3,1,1,0,response(new byte[]{8,1})),BindingReply.Kind.IDENTIFIER_MISSING);
        check(routed(2,3,1,1,0,response(new byte[]{8,1,24,1})),BindingReply.Kind.VENDOR_ERROR);
    }
    @Test public void fragmentedSourceTwoBindingSurvivesSplitReads(){
        byte[] body=found(new byte[]{65,66});byte[] wire=cat(routed(2,3,5,2,0,Arrays.copyOfRange(body,0,5)),routed(2,3,5,2,1,Arrays.copyOfRange(body,5,body.length)));
        try(BindingResponseDecoder decoder=new BindingResponseDecoder()){
            for(byte b:wire)decoder.accept(new byte[]{b},1);
            try(BindingReply reply=decoder.takeReply()){assertNotNull(reply);assertArrayEquals(new byte[]{65,66},reply.copyIdentifier());}
        }
    }
    @Test public void sourceAssembliesDoNotJoinEvenWithSameSequence(){
        check(cat(frame(7,2,0,new byte[]{16}),routed(2,3,7,2,1,new byte[]{24})),BindingReply.Kind.MALFORMED);
    }
    @Test public void interleavedStatusAndBindingHaveIndependentSequenceAndIndex(){
        byte[] status=new byte[]{16,20};byte[] bind=found(new byte[]{65});
        check(cat(routed(2,3,7,2,0,Arrays.copyOfRange(status,0,1)),frame(7,2,0,Arrays.copyOfRange(bind,0,5)),routed(2,3,7,2,1,Arrays.copyOfRange(status,1,2)),frame(7,2,1,Arrays.copyOfRange(bind,5,bind.length))),BindingReply.Kind.ACCESS_FOUND);
    }
    @Test public void unknownPhoneAndWrongDestinationSubjectsCannotGrantAccess(){
        for(int source:new int[]{0,3,99})check(routed(source,3,1,1,0,found(new byte[]{65})),BindingReply.Kind.MALFORMED);
        check(routed(2,1,1,1,0,found(new byte[]{65})),BindingReply.Kind.MALFORMED);
    }
    @Test public void sourceTwoCrcCheckedBeforePayloadIsTrusted(){
        byte[] bad=routed(2,3,1,1,0,found(new byte[]{65}));bad[bad.length-1]^=1;
        try(BindingResponseDecoder decoder=new BindingResponseDecoder()){decoder.accept(bad,bad.length);try(BindingReply reply=decoder.takeReply()){assertEquals(BindingReply.Kind.MALFORMED,reply.kind);}}
    }
    @Test public void sourceTwoWrongOrderSequenceAndMalformedPayloadFailClosed(){
        check(cat(routed(2,3,1,2,0,new byte[]{16}),routed(2,3,2,2,1,new byte[]{24})),BindingReply.Kind.MALFORMED);
        check(cat(routed(2,3,1,2,0,new byte[]{16}),routed(2,3,1,2,0,new byte[]{24})),BindingReply.Kind.MALFORMED);
        check(routed(2,3,1,1,0,response(new byte[]{8,2})),BindingReply.Kind.MALFORMED);
        check(routed(2,3,1,1,0,found(new byte[]{0})),BindingReply.Kind.MALFORMED);
    }
    @Test public void attendedEmptyBindingStatusHasValidEnvelopeButCannotGrantAccess(){
        // Captured 2026-10-06: no identifier, device address or private owner data.
        byte[] wire={(byte)0xaa,0x55,0x01,0x35,0,1,0,0,1,3,0,9,0x10,0x18,(byte)0xda,1,4,8,3,0x22,0,(byte)0xeb,0x0a};
        for(boolean split:new boolean[]{false,true})try(BindingResponseDecoder decoder=new BindingResponseDecoder()){
            if(split)for(byte b:wire)decoder.accept(new byte[]{b},1);else decoder.accept(wire,wire.length);
            try(BindingReply reply=decoder.takeReply()){
                assertNotNull(reply);assertEquals(BindingReply.Kind.UNBOUND,reply.kind);
                try{reply.copyIdentifier();fail("Empty status cannot provide access");}catch(IllegalStateException expected){}
            }
        }
        byte[] corrupt=wire.clone();corrupt[corrupt.length-1]^=1;
        try(BindingResponseDecoder decoder=new BindingResponseDecoder()){
            decoder.accept(corrupt,corrupt.length);try(BindingReply reply=decoder.takeReply()){
                assertEquals(BindingReply.Kind.MALFORMED,reply.kind);
            }
        }
    }
    private void check(byte[] wire,BindingReply.Kind kind){try(BindingResponseDecoder decoder=new BindingResponseDecoder()){decoder.accept(wire,wire.length);try(BindingReply reply=decoder.takeReply()){assertNotNull(reply);assertEquals(kind,reply.kind);}}}
}
