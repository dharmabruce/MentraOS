package com.mentra.bluetoothsdk.sgcs.inmo;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.mentra.bluetoothsdk.sgcs.inmo.Fixtures.*;
public final class BindingReplyParserTest {
    @Test public void opaqueValueAndReorderedFieldsArePreservedAndRedacted(){byte[] value={(byte)255,0,65};
        try(BindingReply reply=BindingReplyParser.parse(response(cat(field(18,value),new byte[]{24,0,8,1})))){
            assertEquals(BindingReply.Kind.ACCESS_FOUND,reply.kind);assertArrayEquals(value,reply.copyIdentifier());assertFalse(reply.toString().contains("65"));
            byte[] copy=reply.copyIdentifier();copy[0]=0;assertArrayEquals(value,reply.copyIdentifier());reply.close();try{reply.copyIdentifier();fail();}catch(IllegalStateException expected){}
        }}
    @Test public void unboundMissingAndVendorErrorsFailClosed(){
        check(response(new byte[]{8,0}),BindingReply.Kind.UNBOUND);check(response(new byte[]{8,1}),BindingReply.Kind.IDENTIFIER_MISSING);
        check(response(cat(new byte[]{8,1},field(18,new byte[]{65}),new byte[]{24,2})),BindingReply.Kind.VENDOR_ERROR);
        check(response(field(18,new byte[]{65})),BindingReply.Kind.MALFORMED);
    }
    @Test public void invalidIdentifierAndDuplicateFieldsAreRejected(){
        check(found(new byte[0]),BindingReply.Kind.IDENTIFIER_MISSING);check(found(new byte[]{0}),BindingReply.Kind.MALFORMED);
        check(found(new byte[257]),BindingReply.Kind.MALFORMED);check(response(new byte[]{8,2}),BindingReply.Kind.MALFORMED);
        check(response(new byte[]{8,1,8,1}),BindingReply.Kind.MALFORMED);
        check(cat(found(new byte[]{65}),new byte[]{16,24}),BindingReply.Kind.MALFORMED);
        check(cat(found(new byte[]{65}),new byte[]{26,0}),BindingReply.Kind.MALFORMED);
    }
    @Test public void unrelatedEchoReconnectAndMalformedProtobufNeverSave(){
        check(new byte[]{16,2},BindingReply.Kind.UNRELATED);
        check(cat(new byte[]{16,24},field(218,new byte[]{8,0})),BindingReply.Kind.UNRELATED);
        check(cat(new byte[]{16,24},field(218,new byte[]{8,5})),BindingReply.Kind.UNRELATED);
        for(byte[] bad:new byte[][]{{0},{16,(byte)128},{16,(byte)128,0},{16,24,(byte)218,1,127},{16,24,(byte)218,1,1,0},{16,24,(byte)218,1,2,8,(byte)255}})check(bad,BindingReply.Kind.MALFORMED);
        check(new byte[4097],BindingReply.Kind.LIMIT_EXCEEDED);
    }
    @Test public void maximumOpaqueLengthAccepted(){byte[] id=new byte[256];Arrays.fill(id,(byte)7);check(found(id),BindingReply.Kind.ACCESS_FOUND);}
    @Test public void validUnrelatedStatusAndReconnectPayloadDoNotAbortQuery(){
        check(new byte[]{16,20,(byte)186,1,6,8,6,58,2,8,80},BindingReply.Kind.UNRELATED);
        check(new byte[]{16,33,(byte)162,2,2,8,10},BindingReply.Kind.UNRELATED);
        check(cat(new byte[]{16,24},field(218,cat(new byte[]{8,5},field(50,new byte[]{8,1})))),BindingReply.Kind.UNRELATED);
        check(cat(new byte[]{16,24},field(218,cat(new byte[]{8,1},field(18,new byte[]{65})))),BindingReply.Kind.UNRELATED);
        check(cat(new byte[]{16,24},field(218,cat(new byte[]{8,3},field(34,cat(new byte[]{8,1},field(18,new byte[]{65}))),field(50,new byte[]{8,1})))),BindingReply.Kind.MALFORMED);
    }
    @Test public void onlyExactlyEmptyPresentStatusUsesVendorUnboundDefault(){
        check(response(new byte[0]),BindingReply.Kind.UNBOUND);
        check(cat(new byte[]{16,24},field(218,new byte[]{8,3})),BindingReply.Kind.IDENTIFIER_MISSING);
        check(response(new byte[]{24,0}),BindingReply.Kind.MALFORMED);
        check(response(new byte[]{24,1}),BindingReply.Kind.VENDOR_ERROR);
        check(response(new byte[]{32,0}),BindingReply.Kind.MALFORMED);
        check(response(field(18,new byte[0])),BindingReply.Kind.MALFORMED);
        check(response(field(18,new byte[]{65})),BindingReply.Kind.MALFORMED);
        check(cat(new byte[]{16,24},field(218,new byte[]{8,3,34,0,34,0})),BindingReply.Kind.MALFORMED);
        check(cat(new byte[]{16,24},field(218,new byte[]{8,3,8,3,34,0})),BindingReply.Kind.MALFORMED);
        check(cat(response(new byte[0]),new byte[]{16,24}),BindingReply.Kind.MALFORMED);
        check(response(new byte[]{0}),BindingReply.Kind.MALFORMED);
    }
    private void check(byte[] message,BindingReply.Kind kind){try(BindingReply reply=BindingReplyParser.parse(message)){assertEquals(kind,reply.kind);}}
}
