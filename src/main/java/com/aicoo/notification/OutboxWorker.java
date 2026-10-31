package com.aicoo.notification;

import com.aicoo.common.*;
import com.aicoo.security.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.aicoo.common.Db.*;

/** PostgreSQL is both the durable outbox and fan-out queue; SKIP LOCKED supports multiple workers. */
@Service
public class OutboxWorker {
    private final Db db;private final Policy policy;private final TransactionTemplate transactions;private final MeterRegistry metrics;private final boolean enabled;
    public OutboxWorker(Db db,Policy policy,TransactionTemplate transactions,MeterRegistry metrics,@Value("${app.outbox-enabled:true}")boolean enabled){this.db=db;this.policy=policy;this.transactions=transactions;this.metrics=metrics;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${app.outbox-interval-ms:1000}")
    public void scheduled(){if(enabled)for(int i=0;i<32&&dispatchOne();i++);}

    public boolean dispatchOne() {
        var claimed=new AtomicReference<UUID>();
        try {
            return Boolean.TRUE.equals(transactions.execute(status->{
                var pending=db.one("SELECT * FROM outbox_messages WHERE published_at IS NULL AND dead_lettered_at IS NULL AND next_attempt_at<=now() ORDER BY CASE payload->>'severity' WHEN 'immediate' THEN 0 WHEN 'caution' THEN 1 WHEN 'unknown' THEN 2 ELSE 3 END,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",Map.of());
                if(pending.isEmpty())return false;
                var task=pending.get();claimed.set(id(task,"id"));publish(task);
                db.update("UPDATE outbox_messages SET published_at=now(),last_error_code=NULL WHERE id=:id",p("id",claimed.get()));
                metrics.counter("aicoo.outbox.published").increment();return true;
            }));
        } catch(Exception ex) {
            metrics.counter("aicoo.outbox.failures").increment();
            if(claimed.get()!=null)try {
                transactions.executeWithoutResult(status->db.update("UPDATE outbox_messages SET retry_count=retry_count+1,last_error_code='DELIVERY_FAILED',next_attempt_at=now()+make_interval(secs=>least(300,power(2,retry_count+1)::int)),dead_lettered_at=CASE WHEN retry_count+1>=5 THEN now() ELSE NULL END WHERE id=:id AND published_at IS NULL",p("id",claimed.get())));
            } catch(Exception ignored) { /* database unavailable: keep original durable row for recovery */ }
            return false;
        }
    }
    @SuppressWarnings("unchecked")
    private void publish(Map<String,Object> task) {
        var payload=(Map<String,Object>)task.get("payload");UUID child=UUID.fromString((String)payload.get("childId"));
        for(String recipient:(List<String>)payload.get("recipients")) {
            UUID member=UUID.fromString(recipient);Policy.Actor actor;
            try {actor=policy.memberActor(member);}catch(ApiException revoked){continue;}
            if(!actor.workspaceId().equals(task.get("workspace_id"))||!policy.allowed(actor,child,"notification:receive")||!policy.allowed(actor,child,"detection:read"))continue;
            var params=p("id",UUID.randomUUID(),"event",task.get("aggregate_id"),"ws",actor.workspaceId(),"m",member);
            db.update("INSERT INTO notifications(id,event_id,workspace_id,recipient_membership_id,channel,delivery_status,sent_at) VALUES(:id,:event,:ws,:m,'in_app','sent',now()) ON CONFLICT(event_id,recipient_membership_id,channel) DO NOTHING",params);
            boolean push=db.one("SELECT push_enabled FROM notification_preferences WHERE membership_id=:m",p("m",member)).map(r->Boolean.TRUE.equals(r.get("push_enabled"))).orElse(false);
            // No push provider is configured. Persist a failure, never claim a notification was sent.
            if(push){params.put("id",UUID.randomUUID());db.update("INSERT INTO notifications(id,event_id,workspace_id,recipient_membership_id,channel,delivery_status) VALUES(:id,:event,:ws,:m,'push','failed') ON CONFLICT(event_id,recipient_membership_id,channel) DO NOTHING",params);metrics.counter("aicoo.push.unconfigured").increment();}
            db.update("INSERT INTO realtime_messages(message_id,outbox_id,membership_id,workspace_id,child_id,event_id,type,severity,occurred_at) VALUES(:id,:outbox,:m,:ws,:child,:e,:type,:severity,:at) ON CONFLICT(outbox_id,membership_id) DO NOTHING",p("id",UUID.randomUUID(),"outbox",task.get("id"),"m",member,"ws",actor.workspaceId(),"child",child,"e",task.get("aggregate_id"),"type",task.get("topic"),"severity",payload.get("severity"),"at",task.get("created_at")));
            metrics.timer("aicoo.notification.latency").record(java.time.Duration.between(instant(task,"created_at"),Instant.now()));
        }
    }
    public Object health(){return db.required("SELECT count(*) FILTER(WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS pending,count(*) FILTER(WHERE dead_lettered_at IS NOT NULL) AS dead_lettered,min(created_at) FILTER(WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS oldest_pending_at FROM outbox_messages",Map.of(),"OUTBOX_UNAVAILABLE");}
    public Object retry(UUID id){int changed=db.update("UPDATE outbox_messages SET dead_lettered_at=NULL,retry_count=0,next_attempt_at=now(),last_error_code=NULL WHERE id=:id AND dead_lettered_at IS NOT NULL AND published_at IS NULL",p("id",id));if(changed==0)throw ApiException.notFound("DEAD_LETTER_NOT_FOUND");return p("queued",true);}
}
