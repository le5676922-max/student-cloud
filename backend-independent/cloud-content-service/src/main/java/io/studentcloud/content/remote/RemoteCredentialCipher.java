package io.studentcloud.content.remote;

import io.studentcloud.common.ApplicationFault;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Credentials are authenticated to one mount and never serialized into a public DTO. */
final class RemoteCredentialCipher {
    record Secrets(String accessKey,String secretKey,String sessionToken) {
        @Override public String toString(){return "RemoteCredentials[redacted]";}
    }
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();

    RemoteCredentialCipher(String encoded){
        byte[] decoded=null;
        try{if(encoded!=null&&!encoded.isBlank())decoded=Base64.getDecoder().decode(encoded.trim());}catch(IllegalArgumentException ignored){}
        if(decoded!=null&&decoded.length!=32){Arrays.fill(decoded,(byte)0);decoded=null;}
        key=decoded;
    }
    void requireReady(){if(key==null)throw unavailable();}
    String encrypt(long mountId,Secrets secrets){
        requireReady();byte[] plain=null;
        try{
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)){output.writeUTF(secrets.accessKey());output.writeUTF(secrets.secretKey());output.writeUTF(secrets.sessionToken());}
            plain=bytes.toByteArray();byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher cipher=cipher(Cipher.ENCRYPT_MODE,mountId,nonce);byte[] encrypted=cipher.doFinal(plain);
            byte[] packed=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,packed,0,nonce.length);System.arraycopy(encrypted,0,packed,nonce.length,encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        }catch(Exception failure){throw unavailable();}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    Secrets decrypt(long mountId,String encoded){
        requireReady();byte[] packed=null,plain=null;
        try{
            packed=Base64.getDecoder().decode(encoded);if(packed.length<29)throw new IllegalArgumentException();
            byte[] nonce=Arrays.copyOf(packed,12);plain=cipher(Cipher.DECRYPT_MODE,mountId,nonce).doFinal(packed,12,packed.length-12);
            try(var input=new DataInputStream(new ByteArrayInputStream(plain))){
                Secrets result=new Secrets(input.readUTF(),input.readUTF(),input.readUTF());if(input.available()!=0)throw new IllegalArgumentException();return result;
            }
        }catch(Exception failure){throw unavailable();}finally{if(plain!=null)Arrays.fill(plain,(byte)0);if(packed!=null)Arrays.fill(packed,(byte)0);}
    }
    private Cipher cipher(int mode,long mountId,byte[] nonce)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(("student-cloud-remote-v1:"+mountId).getBytes(StandardCharsets.US_ASCII));return cipher;
    }
    private static ApplicationFault unavailable(){return new ApplicationFault("STORAGE_CREDENTIALS_UNAVAILABLE",503,"远程存储凭据不可用，请由运维配置有效的 SC_STORAGE_MASTER_KEY 后重试");}
}
