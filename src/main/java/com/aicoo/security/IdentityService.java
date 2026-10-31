package com.aicoo.security;

import com.aicoo.common.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service public class IdentityService {
    public record Signup(@Email @Size(max=254)String email,@NotBlank @Size(min=8,max=72)String password,@Size(max=32)String username){
        @AssertTrue public boolean isIdentifierPresent(){return (username!=null&&!username.isBlank()) || (email!=null&&!email.isBlank());}
    }
    public record Login(@Size(max=254)String username,@Email @Size(max=254)String email,@NotBlank @Size(min=8,max=72)String password){
        @AssertTrue public boolean isIdentifierValid(){return (username!=null&&!username.isBlank()) ^ (email!=null&&!email.isBlank());}
        public String identifier(){return username!=null&&!username.isBlank()?username:email;}
    }
    private final Db db;private final Secrets secrets;private final PasswordEncoder passwords;private final JwtEncoder encoder;private final MailDelivery mail;private final Audit audit;private final String issuer;
    private final String dummyHash;
    public IdentityService(Db db,Secrets secrets,PasswordEncoder passwords,JwtEncoder encoder,MailDelivery mail,Audit audit,@Value("${app.issuer}")String issuer){this.db=db;this.secrets=secrets;this.passwords=passwords;this.encoder=encoder;this.mail=mail;this.audit=audit;this.issuer=issuer;dummyHash=passwords.encode(Secrets.random());}
    @Transactional public void limit(String key){String bucket=Secrets.hash(key);var row=db.required("INSERT INTO rate_limits(bucket,window_start,attempts) VALUES(:b,now(),1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN rate_limits.window_start<now()-interval '15 minutes' THEN 1 ELSE rate_limits.attempts+1 END, window_start=CASE WHEN rate_limits.window_start<now()-interval '15 minutes' THEN now() ELSE rate_limits.window_start END RETURNING attempts",p("b",bucket),"RATE_LIMIT_ERROR");if(((Number)row.get("attempts")).intValue()>15)throw new ApiException(429,"RATE_LIMITED","잠시 후 다시 시도해 주세요.");}
    private String email(String input){return input.trim().toLowerCase(Locale.ROOT);}
    private void passwordLength(String password){if(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiException.invalid("PASSWORD_TOO_LONG");}
    @Transactional public Map<String,Object> signup(Signup input){passwordLength(input.password());String email=input.email()==null||input.email().isBlank()?null:email(input.email());if(db.one("SELECT id FROM users WHERE email=:email",p("email",email)).isPresent())throw ApiException.conflict("ACCOUNT_UNAVAILABLE");
        UUID user=UUID.randomUUID(),workspace=UUID.randomUUID(),member=UUID.randomUUID();
        boolean legacy=input.username()==null;
        String base=legacy?email.substring(0,email.indexOf('@')):input.username().trim().toLowerCase(Locale.ROOT);
        if(!base.matches("[a-z0-9][a-z0-9._-]{0,31}")) {
            if(!legacy)throw new ApiException(422,"INVALID_USERNAME","아이디는 영문·숫자로 시작하는 1~32자의 영문, 숫자, 점, 밑줄, 하이픈으로 입력해 주세요.");
            base="user_"+user.toString().replace("-","").substring(0,12);
        }
        String username=base,hash=passwords.encode(input.password());int counter=1;
        // ON CONFLICT also handles simultaneous registrations without leaking a DB error.
        while(db.update("INSERT INTO users(id,email,username,password_hash) VALUES(:id,:email,:username,:hash) ON CONFLICT DO NOTHING",p("id",user,"email",email,"username",username,"hash",hash))==0) {
            if(db.one("SELECT id FROM users WHERE email=:email",p("email",email)).isPresent())throw ApiException.conflict("ACCOUNT_UNAVAILABLE");
            if(!legacy)throw new ApiException(409,"USERNAME_UNAVAILABLE","이미 사용 중인 아이디예요. 다른 아이디를 입력해 주세요.");
            if(++counter>10000)throw ApiException.conflict("ACCOUNT_UNAVAILABLE");
            String suffix="_"+counter;username=base.substring(0,Math.min(base.length(),32-suffix.length()))+suffix;
        }
        db.update("INSERT INTO workspaces(id,type,name,created_by) VALUES(:id,'PERSONAL','우리 가족',:user)",p("id",workspace,"user",user));
        db.update("INSERT INTO memberships(id,workspace_id,user_id,role) VALUES(:id,:ws,:user,'owner')",p("id",member,"ws",workspace,"user",user));
        var result=new LinkedHashMap<>(newSession(user,UUID.randomUUID()));result.put("workspaceId",workspace);result.put("membershipId",member);result.put("emailVerified",false);audit.record(workspace,user,"auth.signup","user",user);return result;
    }
    @Transactional public Map<String,Object> login(Login input){
        passwordLength(input.password());String identifier=email(input.identifier());
        // '@' cannot appear in a username, so legacy email login is unambiguous.
        String column=identifier.contains("@")?"email":"username";
        var user=db.one("SELECT * FROM users WHERE "+column+"=:identifier AND status='active'",p("identifier",identifier));
        String hash=user.map(u->text(u,"password_hash")).orElse(dummyHash);boolean match=passwords.matches(input.password(),hash);
        if(user.isEmpty()||!match)throw new ApiException(401,"INVALID_CREDENTIALS","아이디 또는 비밀번호를 확인해 주세요.");
        return newSession(id(user.get(),"id"),UUID.randomUUID());
    }
    private Map<String,Object> newSession(UUID user,UUID family){UUID sid=UUID.randomUUID();String refresh=Secrets.random();Instant now=Instant.now();db.update("INSERT INTO user_sessions(id,user_id,family_id,token_hash,expires_at) VALUES(:id,:user,:family,:hash,:expires)",p("id",sid,"user",user,"family",family,"hash",Secrets.hash(refresh),"expires",now.plus(Duration.ofDays(14))));
        var claims=JwtClaimsSet.builder().issuer(issuer).subject(user.toString()).audience(List.of("aicoo-api")).issuedAt(now).expiresAt(now.plusSeconds(900)).claim("sid",sid.toString()).build();String access=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        var profile=db.required("SELECT username,email,email_verified FROM users WHERE id=:id",p("id",user),"USER_NOT_FOUND");
        return p("accessToken",access,"refreshToken",refresh,"tokenType","Bearer","expiresIn",900,"sessionId",sid,"userId",user,"username",profile.get("username"),"email",profile.get("email"),"emailVerified",profile.get("email_verified"));
    }
    @Transactional(noRollbackFor=ApiException.class) public Map<String,Object> refresh(String token){var session=db.one("SELECT * FROM user_sessions WHERE token_hash=:hash FOR UPDATE",p("hash",Secrets.hash(token)));if(session.isEmpty())throw ApiException.unauthorized();var old=session.get();
        if(old.get("revoked_at")!=null){db.update("UPDATE user_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE family_id=:family",p("family",old.get("family_id")));db.update("UPDATE stream_access_tokens SET revoked_at=now() WHERE session_id IN(SELECT id FROM user_sessions WHERE family_id=:family)",p("family",old.get("family_id")));throw new ApiException(401,"REFRESH_REUSE_DETECTED","세션이 만료되었습니다. 다시 로그인해 주세요.");}
        if(instant(old,"expires_at").isBefore(Instant.now())||db.one("SELECT id FROM users WHERE id=:id AND status='active'",p("id",old.get("user_id"))).isEmpty())throw ApiException.unauthorized();
        Map<String,Object> next=newSession(id(old,"user_id"),id(old,"family_id"));db.update("UPDATE user_sessions SET revoked_at=now(),replaced_by=:next WHERE id=:id",p("next",next.get("sessionId"),"id",old.get("id")));return next;
    }
    @Transactional public void logout(Jwt jwt){UUID session=UUID.fromString(jwt.getClaimAsString("sid"));db.update("UPDATE user_sessions SET revoked_at=now() WHERE id=:id AND user_id=:user",p("id",session,"user",UUID.fromString(jwt.getSubject())));db.update("UPDATE stream_access_tokens SET revoked_at=now() WHERE session_id=:id",p("id",session));}
    private Map<String,Object> createVerification(UUID user,String email){String token=Secrets.random();db.update("UPDATE email_verifications SET consumed_at=now() WHERE user_id=:u AND consumed_at IS NULL",p("u",user));db.update("INSERT INTO email_verifications(id,user_id,token_hash,expires_at) VALUES(:id,:u,:hash,:expires)",p("id",UUID.randomUUID(),"u",user,"hash",Secrets.hash(token),"expires",Instant.now().plusSeconds(3600)));mail.deliver(email,"이메일 확인",token);return mail.result("verificationToken",token);}
    @Transactional public Map<String,Object> requestVerification(String email){var user=db.one("SELECT * FROM users WHERE email=:email AND status='active' AND NOT email_verified",p("email",email(email)));if(user.isEmpty())return Map.of("delivery","requested");return createVerification(id(user.get(),"id"),text(user.get(),"email"));}
    @Transactional public void verify(String token){var row=db.required("SELECT * FROM email_verifications WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at>now() FOR UPDATE",p("hash",Secrets.hash(token)),"VERIFICATION_INVALID");db.update("UPDATE users SET email_verified=true WHERE id=:id",p("id",row.get("user_id")));db.update("UPDATE email_verifications SET consumed_at=now() WHERE id=:id",p("id",row.get("id")));}
}
