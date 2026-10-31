package com.aicoo.care;

import com.aicoo.common.*;
import com.aicoo.detection.EventService;
import com.aicoo.monitoring.MonitoringService;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class DashboardService {
    private final Db db;private final Policy policy;private final MonitoringService monitoring;private final EventService events;private final CareService care;
    public DashboardService(Db db,Policy policy,MonitoringService monitoring,EventService events,CareService care){this.db=db;this.policy=policy;this.monitoring=monitoring;this.events=events;this.care=care;}
    public Object dashboard(Actor actor,UUID selected){
        List<UUID> children;if(selected!=null){policy.require(actor,selected,"child:read");children=List.of(selected);}else children=policy.accessibleChildren(actor,"child:read");
        var cards=new ArrayList<Map<String,Object>>();
        for(UUID child:children){
            var card=new LinkedHashMap<>(care.child(actor,child));boolean detection=policy.allowed(actor,child,"detection:read"),monitor=policy.allowed(actor,child,"monitor:live");
            var active=detection?db.list("SELECT e.* FROM detection_events e WHERE e.workspace_id=:ws AND e.child_id=:child AND e.status<>'resolved' ORDER BY CASE severity WHEN 'immediate' THEN 0 WHEN 'caution' THEN 1 WHEN 'unknown' THEN 2 ELSE 3 END,started_at DESC,id DESC",p("ws",actor.workspaceId(),"child",child)):List.<Map<String,Object>>of();
            var snapshot=monitor?monitoring.snapshot(actor.workspaceId(),child):null;
            boolean observed=snapshot!=null&&"live".equals(snapshot.get("streamStatus"));
            boolean known=observed&&snapshot.get("safety") instanceof Map<?,?> safety&&!safety.containsValue("unknown");
            String severity="unknown";
            if(detection){
                if(!active.isEmpty())severity=text(active.getFirst(),"severity");
                else if(known)severity=((Map<?,?>)snapshot.get("safety")).values().stream().map(Object::toString).min(Comparator.comparingInt(EventService::priority)).orElse("unknown");
                if(!known&&severity.equals("stable"))severity="unknown";
            }
            int unacknowledged=0;if(detection)unacknowledged=((Number)db.required("SELECT count(*) AS count FROM detection_events e WHERE e.workspace_id=:ws AND e.child_id=:child AND e.status<>'resolved' AND NOT EXISTS(SELECT 1 FROM event_acknowledgements a WHERE a.event_id=e.id AND a.membership_id=:m)",p("ws",actor.workspaceId(),"child",child,"m",actor.membershipId()),"COUNT_UNAVAILABLE").get("count")).intValue();
            String headline=switch(severity){case "immediate"->"아이의 상태를 즉시 확인해 주세요";case "caution"->"확인이 필요한 신호가 있어요";case "stable"->"현재 수신된 신호에 새 위험 감지가 없어요";default->"현재 상태를 확인할 수 없어요";};
            card.put("severity",severity);card.put("headline",headline);card.put("unacknowledgedCount",detection?unacknowledged:null);card.put("monitoring",snapshot);
            card.put("priorityEvent",active.isEmpty()?null:events.eventView(active.getFirst()));
            card.put("nextAction",active.isEmpty()?p("type","check_child","childId",child):p("type","review_event","eventId",active.getFirst().get("id")));
            card.put("routines",policy.allowed(actor,child,"routine:read")?care.routines(actor,child,LocalDate.now(ZoneId.of("Asia/Seoul"))):null);cards.add(card);
        }
        cards.sort(Comparator.comparingInt((Map<String,Object> c)->EventService.priority((String)c.get("severity"))).thenComparing(c->c.get("id").toString()));
        return p("workspaceId",actor.workspaceId(),"items",cards,"generatedAt",Instant.now(),"empty",cards.isEmpty());
    }
}
