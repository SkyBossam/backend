package com.aicoo.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component public class Secrets {
    public final byte[] jwtKey; public final String workerKey; public final boolean production;
    public Secrets(@Value("${app.jwt-secret}")String jwt,@Value("${app.worker-key}")String worker,@Value("${app.environment}")String environment,@Value("${app.mail-mode}")String mailMode){
        if(!Set.of("development","local","test","staging","production").contains(environment))throw new IllegalStateException("APP_ENV must be development, local, test, staging or production.");
        production=environment.equals("production")||environment.equals("staging");
        if(production&&(jwt.length()<32||worker.length()<32||!mailMode.equals("smtp")))throw new IllegalStateException("Production requires JWT_SECRET, WORKER_KEY (32+ characters), and SMTP mail.");
        jwtKey=(jwt.isBlank()?random():jwt).getBytes(StandardCharsets.UTF_8);workerKey=worker.isBlank()?random():worker;
        if(jwtKey.length<32)throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes.");
    }
    public static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public String sign(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(jwtKey,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public String emailHash(String value){return sign("email:"+value.trim().toLowerCase(Locale.ROOT));}
    public static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}
