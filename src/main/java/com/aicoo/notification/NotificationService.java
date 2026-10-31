package com.aicoo.notification;

import com.aicoo.common.*;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class NotificationService {
    public record Preferences(Boolean crySound,Boolean pushEnabled,Boolean importantAlerts){}
    private final Db db;private final Policy policy;private final Cursors cursors;
    public NotificationService(Db db,Policy policy,Cursors cursors){this.db=db;this.policy=policy;this.cursors=cursors;}
    public Set<UUID> children(Actor actor){var children=new TreeSet<UUID>();for(UUID child:policy.accessibleChildren(actor,"notification:receive"))if(policy.allowed(actor,child,"detection:read"))children.add(child);return children;}
    public Object preferences(Actor actor){var row=db.one("SELECT * FROM notification_preferences WHERE membership_id=:m",p("m",actor.membershipId()));return p("crySound",row.map(r->r.get("cry_sound")).orElse(true),"pushEnabled",row.map(r->r.get("push_enabled")).orElse(false),"importantAlerts",true,"pushDeliveryAvailable",false);}
    @Transactional public Object preferences(Actor actor,Preferences body){if(Boolean.FALSE.equals(body.importantAlerts()))throw ApiException.invalid("IMPORTANT_ALERTS_REQUIRED");db.update("INSERT INTO notification_preferences(membership_id,cry_sound,push_enabled) VALUES(:m,COALESCE(:cry,true),COALESCE(:push,false)) ON CONFLICT(membership_id) DO UPDATE SET cry_sound=COALESCE(:cry,notification_preferences.cry_sound),push_enabled=COALESCE(:push,notification_preferences.push_enabled),updated_at=now()",p("m",actor.membershipId(),"cry",body.crySound(),"push",body.pushEnabled()));return preferences(actor);}
    public Object list(Actor actor,boolean unread,int limit,String cursor){
        int size=Cursors.limit(limit);String context="notifications:"+actor.membershipId()+":"+unread;var position=cursors.decode(context,cursor);var children=children(actor);
        if(children.isEmpty())return p("items",List.of(),"nextCursor",null);
        var params=p("m",actor.membershipId(),"ws",actor.workspaceId(),"children",children,"limit",size+1);String where=unread?" AND n.read_at IS NULL":"";
        if(position!=null){where+=" AND (n.created_at,n.id)<(:at,:id)";params.put("at",position.time());params.put("id",position.id());}
        var rows=db.list("SELECT n.*,e.child_id,e.type,e.severity,e.summary,e.status,a.acknowledged_at FROM notifications n JOIN detection_events e ON e.id=n.event_id LEFT JOIN event_acknowledgements a ON a.event_id=e.id AND a.membership_id=n.recipient_membership_id WHERE n.recipient_membership_id=:m AND n.workspace_id=:ws AND e.child_id IN (:children)"+where+" ORDER BY n.created_at DESC,n.id DESC LIMIT :limit",params);
        boolean more=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        return p("items",page.stream().map(r->view(r,"id","event_id","child_id","channel","delivery_status","read_at","sent_at","created_at","type","severity","summary","status","acknowledged_at")).toList(),"nextCursor",more?cursors.encode(context,instant(page.getLast(),"created_at"),id(page.getLast(),"id")):null);
    }
    @Transactional public Object read(Actor actor,UUID id){
        var row=db.required("SELECT n.*,e.child_id FROM notifications n JOIN detection_events e ON e.id=n.event_id WHERE n.id=:id AND n.recipient_membership_id=:m AND n.workspace_id=:ws",p("id",id,"m",actor.membershipId(),"ws",actor.workspaceId()),"NOTIFICATION_NOT_ACCESSIBLE");
        policy.require(actor,id(row,"child_id"),"notification:receive");policy.require(actor,id(row,"child_id"),"detection:read");
        db.update("UPDATE notifications SET read_at=COALESCE(read_at,now()) WHERE id=:id",p("id",id));return view(db.required("SELECT id,read_at FROM notifications WHERE id=:id",p("id",id),"NOTIFICATION_NOT_FOUND"),"id","read_at");
    }
    public List<Map<String,Object>> replay(Actor actor,long after,Set<UUID> children){
        if(children.isEmpty())return List.of();
        return db.list("SELECT * FROM realtime_messages WHERE membership_id=:m AND workspace_id=:ws AND child_id IN (:children) AND sequence>:seq ORDER BY sequence LIMIT 100",p("m",actor.membershipId(),"ws",actor.workspaceId(),"children",children,"seq",after)).stream().map(r->view(r,"message_id","sequence","type","workspace_id","child_id","event_id","severity","occurred_at")).toList();
    }
}
