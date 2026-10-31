package com.aicoo.common;
import com.aicoo.security.Secrets;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;
import static com.aicoo.common.Db.p;

@Component public class Idempotency {
    private final Db db; public Idempotency(Db db){this.db=db;}
    @SuppressWarnings("unchecked") public Map<String,Object> execute(UUID membership,String key,String operation,Object body,Supplier<Map<String,Object>> work){
        if(key==null||key.isBlank()||key.length()>128)throw ApiException.invalid("IDEMPOTENCY_KEY_REQUIRED");
        db.list("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))",p("lock",membership+":"+key));
        String hash=Secrets.hash(db.json(body));var previous=db.one("SELECT * FROM idempotency_records WHERE membership_id=:m AND request_key=:key",p("m",membership,"key",key));
        if(previous.isPresent()){var row=previous.get();if(!operation.equals(row.get("operation"))||!hash.equals(row.get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED");return (Map<String,Object>)row.get("response");}
        Map<String,Object> result=work.get();db.update("INSERT INTO idempotency_records(membership_id,request_key,operation,request_hash,response) VALUES(:m,:key,:op,:hash,cast(:response as jsonb))",p("m",membership,"key",key,"op",operation,"hash",hash,"response",db.json(result)));return result;
    }
}
