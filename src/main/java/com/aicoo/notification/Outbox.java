package com.aicoo.notification;

import com.aicoo.common.*;
import com.aicoo.security.Policy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class Outbox {
    private final Db db;
    private final Policy policy;
    public Outbox(Db db,Policy policy){this.db=db;this.policy=policy;}

    /** Must commit in the same transaction as the domain event. Recipients are snapshotted now. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(Map<String,Object> event,String topic) {
        UUID workspace=id(event,"workspace_id"),child=id(event,"child_id");
        var recipients=new ArrayList<UUID>();
        for(var row:db.list("SELECT id FROM memberships WHERE workspace_id=:ws AND status='active'",p("ws",workspace))) {
            try {
                var actor=policy.memberActor(id(row,"id"));
                if(policy.allowed(actor,child,"notification:receive")&&policy.allowed(actor,child,"detection:read")) recipients.add(actor.membershipId());
            } catch(ApiException ignored) { /* revoked or disabled member */ }
        }
        db.update("INSERT INTO outbox_messages(id,topic,aggregate_id,workspace_id,payload) VALUES(:id,:topic,:event,:ws,cast(:payload as jsonb))",
            p("id",UUID.randomUUID(),"topic",topic,"event",event.get("id"),"ws",workspace,"payload",db.json(p("recipients",recipients,"severity",event.get("severity"),"childId",child))));
    }
}
