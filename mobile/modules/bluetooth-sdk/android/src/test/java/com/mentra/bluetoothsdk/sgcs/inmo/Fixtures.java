package com.mentra.bluetoothsdk.sgcs.inmo;
import java.io.ByteArrayOutputStream;
final class Fixtures {
    static byte[] cat(byte[]...parts){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part,0,part.length);return out.toByteArray();}
    static byte[] v(int value){ByteArrayOutputStream out=new ByteArrayOutputStream();while(value>127){out.write((value&127)|128);value>>>=7;}out.write(value);return out.toByteArray();}
    static byte[] field(int tag,byte[] value){return cat(v(tag),v(value.length),value);}
    static byte[] response(byte[] status){return cat(new byte[]{16,24},field(218,cat(new byte[]{8,3},field(34,status))));}
    static byte[] found(byte[] id){return response(cat(new byte[]{8,1},field(18,id)));}
    static byte[] frame(int seq,int count,int index,byte[] body){byte[] out=new byte[body.length+14];out[0]=(byte)170;out[1]=85;
        u16(out,2,seq);u16(out,4,count);u16(out,6,index);out[8]=1;out[9]=3;u16(out,10,body.length);System.arraycopy(body,0,out,12,body.length);
        int crc=65535;for(int i=0;i<out.length-2;i++){crc^=(out[i]&255)<<8;for(int j=0;j<8;j++)crc=((crc&32768)!=0?(crc<<1)^0x1021:crc<<1)&65535;}u16(out,out.length-2,crc);return out;}
    static int crc(byte[] bytes){int crc=65535;for(int i=0;i<bytes.length-2;i++){crc^=(bytes[i]&255)<<8;for(int j=0;j<8;j++)crc=((crc&32768)!=0?(crc<<1)^0x1021:crc<<1)&65535;}return crc;}
    static void u16(byte[] bytes,int offset,int value){bytes[offset]=(byte)(value>>8);bytes[offset+1]=(byte)value;}
}
