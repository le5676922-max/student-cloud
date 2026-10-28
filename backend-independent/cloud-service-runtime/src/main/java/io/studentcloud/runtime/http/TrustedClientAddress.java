package io.studentcloud.runtime.http;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;

/** Only a short-lived gateway MAC can supply client addressing; arbitrary forwarding headers are ignored. */
public final class TrustedClientAddress {
    private final byte[] key;
    public TrustedClientAddress(@Value("${student-cloud.services.internal-key:}") String key) {this.key=key.getBytes(StandardCharsets.UTF_8);}
    public String peer(HttpServletRequest request) {
        String ip=request.getHeader("X-StudentCloud-Client-Ip"), at=request.getHeader("X-StudentCloud-Client-At"), signature=request.getHeader("X-StudentCloud-Client-Signature");
        if(ip==null || ip.length()>64 || !ip.matches("[0-9a-fA-F:.]+") || at==null || !at.matches("[0-9]{1,17}")
                || signature==null || !signature.matches("[A-Za-z0-9_-]{43}") || key.length<32) return request.getRemoteAddr();
        try {
            long issued=Long.parseLong(at),now=System.currentTimeMillis();
            if(issued<now-30_000 || issued>now+30_000) return request.getRemoteAddr();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] expected=mac.doFinal((at+'\n'+ip+'\n'+request.getMethod()+'\n'+request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
            if(MessageDigest.isEqual(expected,Base64.getUrlDecoder().decode(signature))) return ip;
        }catch(java.security.GeneralSecurityException|IllegalArgumentException invalid) { /* Invalid context never creates a new client bucket. */ }
        return request.getRemoteAddr();
    }
}
