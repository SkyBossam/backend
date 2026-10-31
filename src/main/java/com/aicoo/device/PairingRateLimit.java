package com.aicoo.device;

import com.aicoo.common.*;
import com.aicoo.security.Secrets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.aicoo.common.Db.*;

@Service
public class PairingRateLimit {
    private final Db db;
    public PairingRateLimit(Db db){this.db=db;}
    // A rejected attempt must still consume quota, independently of a failed approval transaction.
    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=ApiException.class)
    public void check(String bucket,int maximum){
        var row=db.required("INSERT INTO rate_limits(bucket,window_start,attempts) VALUES(:bucket,now(),1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN rate_limits.window_start<now()-interval '15 minutes' THEN 1 ELSE rate_limits.attempts+1 END,window_start=CASE WHEN rate_limits.window_start<now()-interval '15 minutes' THEN now() ELSE rate_limits.window_start END RETURNING attempts",p("bucket",Secrets.hash("pairing:"+bucket)),"RATE_LIMIT_ERROR");
        if(((Number)row.get("attempts")).intValue()>maximum)throw new ApiException(429,"PAIRING_RATE_LIMITED","연결 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.");
    }
}
