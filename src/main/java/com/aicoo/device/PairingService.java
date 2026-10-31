package com.aicoo.device;

import com.aicoo.common.*;
import com.aicoo.security.*;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class PairingService {
    public record Challenge(@NotBlank @Size(max=128)String publicKey,@NotBlank @Pattern(regexp="pair|authenticate|unlink")String purpose,UUID deviceId){}
    public record Proof(@NotBlank @Size(max=128)String publicKey,@NotNull UUID challengeId,@NotBlank @Size(max=128)String nonce,@NotBlank @Size(max=128)String signature){}
    public record Code(@NotBlank @Size(max=24)String code){}
    public record Approval(@NotBlank @Size(max=24)String code,@NotNull UUID childId,@NotBlank @Size(max=80)String name){}
    public record Poll(@NotBlank @Size(max=128)String deviceCode){}
    private final Db db;private final Policy policy;private final Secrets secrets;private final Audit audit;private final Idempotency idem;private final String frontend;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public PairingService(Db db,Policy policy,Secrets secrets,Audit audit,Idempotency idem,@Value("${app.frontend-url:http://localhost:5173}")String frontend){this.db=db;this.policy=policy;this.secrets=secrets;this.audit=audit;this.idem=idem;this.frontend=frontend.replaceAll("/$","");}

    private PublicKey key(String encoded){
        try{byte[] bytes=Base64.getDecoder().decode(encoded);var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));if(!Base64.getEncoder().encodeToString(key.getEncoded()).equals(encoded))throw new IllegalArgumentException();return key;}
        catch(Exception ex){throw ApiException.invalid("INVALID_DEVICE_KEY");}
    }
    public static String proofMessage(String purpose,UUID challenge,String nonce){return "aicoo-device-v1\n"+purpose+"\n"+challenge+"\n"+nonce;}
    public static String proofMessage(String purpose,UUID challenge,String nonce,UUID target){return proofMessage(purpose,challenge,nonce)+(target==null?"":"\n"+target);}
    @Transactional public Object challenge(Challenge body){
        if("unlink".equals(body.purpose())!=(body.deviceId()!=null))throw ApiException.invalid("INVALID_CHALLENGE_TARGET");
        key(body.publicKey());UUID id=UUID.randomUUID();String nonce=Secrets.random();Instant expires=Instant.now().plusSeconds(60);
        db.update("INSERT INTO device_challenges(id,public_key_hash,purpose,nonce_hash,expires_at,target_device_id) VALUES(:id,:key,:purpose,:nonce,:expires,:target)",p("id",id,"key",Secrets.hash(body.publicKey()),"purpose",body.purpose(),"nonce",Secrets.hash(nonce),"expires",expires,"target",body.deviceId()));
        return p("challengeId",id,"nonce",nonce,"expiresAt",expires,"algorithm","Ed25519","message",proofMessage(body.purpose(),id,nonce,body.deviceId()));
    }
    private UUID prove(Proof body,String purpose){
        var publicKey=key(body.publicKey());var challenge=db.one("SELECT * FROM device_challenges WHERE id=:id FOR UPDATE",p("id",body.challengeId())).orElseThrow(()->ApiException.unauthorized());
        if(challenge.get("used_at")!=null||!instant(challenge,"expires_at").isAfter(Instant.now())||!purpose.equals(challenge.get("purpose"))||!Secrets.equal(Secrets.hash(body.publicKey()),text(challenge,"public_key_hash"))||!Secrets.equal(Secrets.hash(body.nonce()),text(challenge,"nonce_hash")))throw ApiException.unauthorized();
        UUID target=challenge.get("target_device_id")==null?null:id(challenge,"target_device_id");
        try{var verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicKey);verifier.update(proofMessage(purpose,body.challengeId(),body.nonce(),target).getBytes(StandardCharsets.UTF_8));if(!verifier.verify(Base64.getDecoder().decode(body.signature())))throw ApiException.unauthorized();}
        catch(ApiException ex){throw ex;}catch(Exception ex){throw ApiException.unauthorized();}
        db.update("UPDATE device_challenges SET used_at=now() WHERE id=:id",p("id",body.challengeId()));
        return target;
    }
    private Map<String,Object> identity(String publicKey){
        String hash=Secrets.hash(publicKey);db.list("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",p("key","device-identity:"+hash));
        db.update("INSERT INTO device_identities(id,public_key,public_key_hash) VALUES(:id,:key,:hash) ON CONFLICT(public_key_hash) DO NOTHING",p("id",UUID.randomUUID(),"key",publicKey,"hash",hash));
        return db.required("SELECT * FROM device_identities WHERE public_key_hash=:hash FOR UPDATE",p("hash",hash),"DEVICE_NOT_FOUND");
    }
    @Transactional public Object start(Proof body){
        prove(body,"pair");var identity=identity(body.publicKey());
        if(identity.get("active_device_id")!=null)throw ApiException.conflict("DEVICE_ALREADY_PAIRED");
        db.update("UPDATE device_pairings SET status='cancelled' WHERE identity_id=:id AND status IN ('pending','approved')",p("id",identity.get("id")));
        StringBuilder code=new StringBuilder();for(int i=0;i<8;i++)code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        String userCode=code.substring(0,4)+"-"+code.substring(4);String deviceCode=Secrets.random();UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);
        db.update("INSERT INTO device_pairings(id,identity_id,user_code_hash,device_code_hash,status,expires_at) VALUES(:id,:identity,:code,:secret,'pending',:expires)",p("id",id,"identity",identity.get("id"),"code",Secrets.hash(code.toString()),"secret",Secrets.hash(deviceCode),"expires",expires));
        return p("pairingId",id,"userCode",userCode,"deviceCode",deviceCode,"expiresAt",expires,"interval",3,"verificationUri",frontend+"/devices","verificationUriComplete",frontend+"/devices#code="+userCode,"fingerprint",text(identity,"public_key_hash").substring(0,12));
    }
    private String normalized(String code){String result=code.replaceAll("[\\s-]","").toUpperCase(Locale.ROOT);if(!result.matches("[A-HJ-NP-Z2-9]{8}"))throw ApiException.invalid("INVALID_PAIRING_CODE");return result;}
    private Map<String,Object> findCode(String code){return db.one("SELECT * FROM device_pairings WHERE user_code_hash=:code",p("code",Secrets.hash(normalized(code)))).orElseThrow(()->ApiException.notFound("PAIRING_CODE_NOT_FOUND"));}
    private void pendingOrOwn(Map<String,Object> row,Actor actor){
        if("cancelled".equals(row.get("status")))throw ApiException.conflict("PAIRING_CANCELLED");
        if(!instant(row,"expires_at").isAfter(Instant.now()))throw ApiException.conflict("PAIRING_EXPIRED");
        if(!"pending".equals(row.get("status"))&&!actor.userId().equals(row.get("approved_by")))throw ApiException.conflict("PAIRING_ALREADY_USED");
    }
    public Object inspect(Actor actor,Code body){policy.verified(actor);var row=findCode(body.code());pendingOrOwn(row,actor);var identity=db.required("SELECT public_key_hash FROM device_identities WHERE id=:id",p("id",row.get("identity_id")),"DEVICE_NOT_FOUND");return p("pairingId",row.get("id"),"status",row.get("status"),"expiresAt",row.get("expires_at"),"fingerprint",text(identity,"public_key_hash").substring(0,12),"deviceType","camera");}
    @Transactional public Object approve(Actor actor,Approval body,String idempotencyKey){
        policy.verified(actor);var link=policy.require(actor,body.childId(),"child:manage");policy.require(actor,body.childId(),"child:read");var found=findCode(body.code());
        var identity=db.required("SELECT * FROM device_identities WHERE id=:id FOR UPDATE",p("id",found.get("identity_id")),"DEVICE_NOT_FOUND");
        var row=db.required("SELECT * FROM device_pairings WHERE id=:id FOR UPDATE",p("id",found.get("id")),"PAIRING_CODE_NOT_FOUND");pendingOrOwn(row,actor);
        return idem.execute(actor.membershipId(),idempotencyKey,"device.pair",p("code",normalized(body.code()),"childId",body.childId(),"name",body.name().trim()),()->{
            if(!"pending".equals(row.get("status")))throw ApiException.conflict("PAIRING_ALREADY_USED");
            if(identity.get("active_device_id")!=null)throw ApiException.conflict("DEVICE_ALREADY_PAIRED");
            UUID device=UUID.randomUUID();db.update("INSERT INTO devices(id,workspace_id,workspace_child_id,child_id,device_type,identity_id,display_name,paired_by) VALUES(:id,:ws,:wc,:child,'camera',:identity,:name,:user)",p("id",device,"ws",actor.workspaceId(),"wc",link.get("id"),"child",body.childId(),"identity",identity.get("id"),"name",body.name().trim(),"user",actor.userId()));
            db.update("UPDATE device_identities SET active_device_id=:device WHERE id=:id",p("device",device,"id",identity.get("id")));
            db.update("UPDATE device_pairings SET status='approved',approved_by=:user,device_id=:device,approved_at=now() WHERE id=:id",p("id",row.get("id"),"user",actor.userId(),"device",device));
            audit.record(actor.workspaceId(),actor.userId(),"device.pair","device",device);
            return p("deviceId",device,"childId",body.childId(),"workspaceId",actor.workspaceId(),"name",body.name().trim(),"status","approved");
        });
    }
    private Map<String,Object> activeDevice(UUID device,boolean lock){
        return db.one("SELECT d.* FROM devices d JOIN device_identities i ON i.active_device_id=d.id AND i.id=d.identity_id JOIN workspace_children wc ON wc.id=d.workspace_child_id JOIN workspaces w ON w.id=d.workspace_id WHERE d.id=:id AND d.revoked_at IS NULL AND wc.link_status='active' AND w.status='active' AND (wc.share_grant_id IS NULL OR EXISTS(SELECT 1 FROM data_share_grants s WHERE s.id=wc.share_grant_id AND s.revoked_at IS NULL AND s.expires_at>now()))"+(lock?" FOR UPDATE OF d":""),p("id",device)).orElseThrow(()->new ApiException(401,"DEVICE_NOT_PAIRED","기기 연결이 해제되었어요. 다시 연결해 주세요."));
    }
    private Map<String,Object> tokenResponse(Map<String,Object> device,String token,Instant expires){return p("accessToken",token,"tokenType","Device","expiresAt",expires,"deviceId",device.get("id"),"workspaceId",device.get("workspace_id"),"childId",device.get("child_id"),"name",device.get("display_name"));}
    @Transactional(noRollbackFor=ApiException.class) public Object poll(Poll body){
        var found=db.one("SELECT * FROM device_pairings WHERE device_code_hash=:hash",p("hash",Secrets.hash(body.deviceCode()))).orElseThrow(()->ApiException.unauthorized());
        db.required("SELECT id FROM device_identities WHERE id=:id FOR UPDATE",p("id",found.get("identity_id")),"DEVICE_NOT_FOUND");
        var row=db.required("SELECT * FROM device_pairings WHERE id=:id FOR UPDATE",p("id",found.get("id")),"PAIRING_CODE_NOT_FOUND");
        if(!instant(row,"expires_at").isAfter(Instant.now()))throw ApiException.conflict("PAIRING_EXPIRED");if("cancelled".equals(row.get("status")))throw ApiException.conflict("PAIRING_CANCELLED");
        if("pending".equals(row.get("status"))){if(row.get("last_polled_at")!=null&&instant(row,"last_polled_at").isAfter(Instant.now().minusSeconds(3)))throw new ApiException(429,"PAIRING_SLOW_DOWN","3초 후 다시 확인해 주세요.");db.update("UPDATE device_pairings SET last_polled_at=now() WHERE id=:id",p("id",row.get("id")));return p("status","pending","interval",3,"expiresAt",row.get("expires_at"));}
        var device=activeDevice(id(row,"device_id"),true);String token=secrets.sign("device-pair-token:"+row.get("id")+":"+body.deviceCode());Instant expires;
        if(row.get("credential_id")==null){UUID credential=UUID.randomUUID();expires=Instant.now().plus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);db.update("INSERT INTO device_credentials(id,device_id,token_hash,expires_at) VALUES(:id,:device,:hash,:expires)",p("id",credential,"device",device.get("id"),"hash",Secrets.hash(token),"expires",expires));db.update("UPDATE device_pairings SET status='consumed',credential_id=:credential WHERE id=:id",p("credential",credential,"id",row.get("id")));}
        else{var credential=db.one("SELECT expires_at FROM device_credentials WHERE id=:id AND token_hash=:hash AND revoked_at IS NULL AND expires_at>now()",p("id",row.get("credential_id"),"hash",Secrets.hash(token))).orElseThrow(()->ApiException.unauthorized());expires=instant(credential,"expires_at");}
        // Lost HTTP responses may retry with the private device code until expiry; user code cannot redeem a token.
        return tokenResponse(device,token,expires);
    }
    @Transactional public Object authenticate(Proof body){
        prove(body,"authenticate");var identity=identity(body.publicKey());if(identity.get("active_device_id")==null)throw new ApiException(401,"DEVICE_NOT_PAIRED","먼저 기기를 연결해 주세요.");var device=activeDevice(id(identity,"active_device_id"),true);
        db.update("UPDATE device_credentials SET revoked_at=now() WHERE device_id=:id AND revoked_at IS NULL",p("id",device.get("id")));String token=Secrets.random();Instant expires=Instant.now().plus(Duration.ofDays(30));
        db.update("INSERT INTO device_credentials(id,device_id,token_hash,expires_at) VALUES(:id,:device,:hash,:expires)",p("id",UUID.randomUUID(),"device",device.get("id"),"hash",Secrets.hash(token),"expires",expires));return tokenResponse(device,token,expires);
    }
    // Call within the gateway transaction; lock serializes in-flight writes with unlinking.
    public Map<String,Object> authorize(String token){
        var credential=db.one("SELECT device_id FROM device_credentials WHERE token_hash=:hash AND revoked_at IS NULL AND expires_at>now()",p("hash",Secrets.hash(token))).orElseThrow(()->new ApiException(401,"DEVICE_TOKEN_INVALID","기기 인증 정보를 확인해 주세요."));
        var device=activeDevice(id(credential,"device_id"),true);
        db.one("SELECT id FROM device_credentials WHERE token_hash=:hash AND revoked_at IS NULL AND expires_at>now()",p("hash",Secrets.hash(token))).orElseThrow(()->ApiException.unauthorized());return device;
    }
    public Object list(Actor actor,UUID child){policy.require(actor,child,"child:read");boolean manage=policy.allowed(actor,child,"child:manage");var rows=db.list("SELECT d.*,i.public_key_hash FROM devices d JOIN device_identities i ON i.id=d.identity_id WHERE d.workspace_id=:ws AND d.child_id=:child AND d.revoked_at IS NULL ORDER BY d.created_at,d.id",p("ws",actor.workspaceId(),"child",child));return p("items",rows.stream().map(r->p("id",r.get("id"),"name",r.get("display_name"),"childId",child,"state",r.get("state"),"connected",r.get("last_seen_at")!=null&&instant(r,"last_seen_at").isAfter(Instant.now().minusSeconds(30)),"lastSeenAt",r.get("last_seen_at"),"pairedAt",r.get("created_at"),"fingerprint",text(r,"public_key_hash").substring(0,12),"canManage",manage)).toList());}
    @Transactional public Object unlink(Actor actor,UUID id){
        var found=db.required("SELECT * FROM devices WHERE id=:id AND workspace_id=:ws",p("id",id,"ws",actor.workspaceId()),"DEVICE_NOT_ACCESSIBLE");policy.require(actor,id(found,"child_id"),"child:manage");policy.verified(actor);
        if(found.get("identity_id")==null)throw ApiException.invalid("DEVICE_NOT_PAIRABLE");
        db.required("SELECT id FROM device_identities WHERE id=:id FOR UPDATE",p("id",found.get("identity_id")),"DEVICE_NOT_FOUND");db.required("SELECT id FROM devices WHERE id=:id FOR UPDATE",p("id",id),"DEVICE_NOT_FOUND");
        revoke(found);
        audit.record(actor.workspaceId(),actor.userId(),"device.unlink","device",id);return p("unlinked",true,"deviceId",id);
    }
    @Transactional public Object unlinkFromDevice(Proof body){
        UUID target=prove(body,"unlink");var identity=identity(body.publicKey());
        // An old request must never disconnect a later approval of the same Pi.
        if(identity.get("active_device_id")!=null&&!target.equals(identity.get("active_device_id")))throw ApiException.conflict("DEVICE_BINDING_CHANGED");
        var device=db.required("SELECT * FROM devices WHERE id=:id AND identity_id=:identity FOR UPDATE",p("id",target,"identity",identity.get("id")),"DEVICE_NOT_ACCESSIBLE");
        boolean alreadyUnlinked=device.get("revoked_at")!=null;
        revoke(device);
        if(!alreadyUnlinked)audit.record(id(device,"workspace_id"),null,"device.unlink.local","device",target);
        return p("unlinked",true,"deviceId",target,"alreadyUnlinked",alreadyUnlinked);
    }
    private void revoke(Map<String,Object> found){
        UUID id=id(found,"id");
        db.update("UPDATE devices SET revoked_at=COALESCE(revoked_at,now()),state='offline' WHERE id=:id",p("id",id));db.update("UPDATE device_identities SET active_device_id=NULL WHERE id=:identity AND active_device_id=:device",p("identity",found.get("identity_id"),"device",id));
        db.update("UPDATE device_credentials SET revoked_at=COALESCE(revoked_at,now()) WHERE device_id=:id",p("id",id));db.update("UPDATE device_pairings SET status='cancelled' WHERE device_id=:id",p("id",id));db.update("UPDATE stream_access_tokens SET revoked_at=now() WHERE device_id=:id AND revoked_at IS NULL",p("id",id));db.update("UPDATE monitoring_sessions SET ended_at=last_updated_at WHERE source_id=:id AND ended_at IS NULL",p("id",id));
    }
}
