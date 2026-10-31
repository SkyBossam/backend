package com.aicoo.detection;

import com.aicoo.common.*;
import com.aicoo.notification.Outbox;
import com.aicoo.security.*;
import com.aicoo.security.Policy.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class EventService {
    public static final Set<String> TYPES=Set.of("cry","face_covered","edge_proximity","movement","camera_obstructed","connection_lost");
    public record Detection(@NotNull UUID workspaceId,@NotNull UUID childId,@NotNull UUID sourceId,
        @NotBlank @Size(max=128) String sourceEventId,@NotBlank @Pattern(regexp="cry|face_covered|edge_proximity|movement|camera_obstructed|connection_lost") String type,
        @NotBlank @Pattern(regexp="immediate|caution|stable|unknown") String severity,@NotNull Instant startedAt,Instant endedAt,
        @DecimalMin("0") @DecimalMax("1") Double confidence,@Size(max=100) String modelVersion,boolean verifiable) {}
    public record Attempt(@NotNull UUID actionId,@NotBlank @Pattern(regexp="attempted|not_attempted") String state,@Size(max=1000) String note) {}
    public record Feedback(@NotNull @Size(max=20) List<@Valid Attempt> attempts,@NotBlank @Pattern(regexp="stopped|reduced|continues|unknown") String response,@Size(max=1000) String note) {}
    public record Resolution(@NotBlank @Pattern(regexp="checked_safe|intervention_done|false_alarm|needs_help") String result,@Size(max=1000) String note) {}
    public record Cause(@NotBlank @Pattern(regexp="hunger|sleepiness|discomfort|illness_check|other") String key,@NotBlank @Size(max=80) String label,@NotBlank @Size(max=500) String reason) {}
    public record Action(@NotBlank @Pattern(regexp="[a-z_]{1,60}") String key,@NotBlank @Size(max=100) String title,@NotBlank @Size(max=500) String explanation,@NotEmpty @Size(max=8) List<@NotBlank @Size(max=300) String> instructions) {}
    public record Analysis(@NotBlank @Pattern(regexp="ready|failed|insufficient_data") String status,@NotBlank @Size(max=100) String modelVersion,@NotNull @Size(max=5) List<@Valid Cause> causes,@NotNull @Size(max=10) List<@Valid Action> actions) {}
    private final Db db; private final Policy policy; private final Audit audit; private final Idempotency idem; private final Cursors cursors; private final Outbox outbox;
    private final double threshold;
    public EventService(Db db,Policy policy,Audit audit,Idempotency idem,Cursors cursors,Outbox outbox,@Value("${app.confidence-threshold:0.6}")double threshold) {
        this.db=db;this.policy=policy;this.audit=audit;this.idem=idem;this.cursors=cursors;this.outbox=outbox;this.threshold=threshold;
    }

    @Transactional
    public Map<String,Object> ingest(Detection body) {
        if(body.startedAt().isAfter(Instant.now().plusSeconds(30))||body.endedAt()!=null&&(body.endedAt().isBefore(body.startedAt())||body.endedAt().isAfter(Instant.now().plusSeconds(30)))) throw ApiException.invalid("INVALID_EVENT_TIME");
        var device=db.required("SELECT d.* FROM devices d JOIN workspace_children wc ON wc.id=d.workspace_child_id JOIN workspaces w ON w.id=d.workspace_id WHERE d.id=:id AND d.workspace_id=:ws AND d.child_id=:child AND wc.link_status='active' AND w.status='active' AND (wc.share_grant_id IS NULL OR EXISTS(SELECT 1 FROM data_share_grants s WHERE s.id=wc.share_grant_id AND s.revoked_at IS NULL AND s.expires_at>now())) FOR UPDATE OF d",p("id",body.sourceId(),"ws",body.workspaceId(),"child",body.childId()),"SOURCE_NOT_ACCESSIBLE");
        String hash=Secrets.hash(db.json(body));
        var receipt=db.one("SELECT * FROM detection_receipts WHERE source_id=:s AND source_event_id=:key",p("s",body.sourceId(),"key",body.sourceEventId()));
        if(receipt.isPresent()) {
            if(!hash.equals(receipt.get().get("request_hash")))throw ApiException.conflict("DETECTION_KEY_REUSED");
            return eventView(db.required("SELECT * FROM detection_events WHERE id=:id",p("id",receipt.get().get("event_id")),"EVENT_NOT_FOUND"));
        }
        boolean verifiable=body.verifiable()&&body.confidence()!=null&&body.confidence()>=threshold&&!Set.of("camera_obstructed","connection_lost").contains(body.type());
        String severity=verifiable?body.severity():"unknown",status=verifiable?"new":"unverifiable";
        // Only join recent, still-open detections from the identical bound source and type.
        var group=db.one("SELECT * FROM detection_events WHERE workspace_id=:ws AND child_id=:child AND source_id=:s AND type=:type AND status IN ('new','acknowledged','checking','unverifiable') AND ended_at IS NULL AND last_observed_at BETWEEN :since AND :at AND verifiable=:verifiable ORDER BY last_observed_at DESC LIMIT 1 FOR UPDATE",p("ws",body.workspaceId(),"child",body.childId(),"s",body.sourceId(),"type",body.type(),"since",body.startedAt().minusSeconds(15),"at",body.startedAt(),"verifiable",verifiable));
        UUID eventId;
        if(group.isPresent()) {
            eventId=id(group.get(),"id");String oldSeverity=text(group.get(),"severity");
            severity=priority(oldSeverity)<priority(severity)?oldSeverity:severity;
            db.update("UPDATE detection_events SET ended_at=:end,last_observed_at=:at,severity=:severity WHERE id=:id",p("id",eventId,"end",body.endedAt(),"at",body.startedAt(),"severity",severity));
        } else {
            eventId=UUID.randomUUID();
            db.update("INSERT INTO detection_events(id,workspace_id,workspace_child_id,child_id,source_id,type,severity,status,started_at,ended_at,last_observed_at,verifiable,model_version,confidence,summary) VALUES(:id,:ws,:wc,:child,:s,:type,:severity,:status,:start,:end,:at,:verifiable,:model,:confidence,:summary)",p("id",eventId,"ws",body.workspaceId(),"wc",device.get("workspace_child_id"),"child",body.childId(),"s",body.sourceId(),"type",body.type(),"severity",severity,"status",status,"start",body.startedAt(),"end",body.endedAt(),"at",body.startedAt(),"verifiable",verifiable,"model",body.modelVersion(),"confidence",body.confidence(),"summary",summary(body.type(),verifiable)));
            history(eventId,null,status,null,"detection.received");
        }
        db.update("INSERT INTO detection_receipts(source_id,source_event_id,event_id,request_hash) VALUES(:s,:key,:event,:hash)",p("s",body.sourceId(),"key",body.sourceEventId(),"event",eventId,"hash",hash));
        var event=db.required("SELECT * FROM detection_events WHERE id=:id",p("id",eventId),"EVENT_NOT_FOUND");
        if(group.isEmpty()||!Objects.equals(group.get().get("severity"),severity))outbox.enqueue(event,group.isEmpty()?"detection.created":"detection.updated");
        return eventView(event);
    }

    public static int priority(String severity) {return switch(severity){case "immediate"->0;case "caution"->1;case "unknown"->2;default->3;};}
    private static String summary(String type,boolean verified) {
        if(!verified)return "화면 또는 신호를 확인할 수 없어요. 아이와 기기 상태를 직접 확인해 주세요.";
        return switch(type){case "cry"->"울음 신호가 감지되었어요. 아이의 상태를 확인해 주세요.";case "face_covered"->"얼굴 가림 신호가 감지되었어요. 즉시 직접 확인해 주세요.";case "edge_proximity"->"가장자리 접근 신호가 감지되었어요. 직접 확인해 주세요.";case "movement"->"움직임 신호가 감지되었어요.";default->"연결 상태를 확인해 주세요.";};
    }
    public Map<String,Object> eventView(Map<String,Object> row) {return view(row,"id","workspace_id","child_id","type","severity","status","started_at","ended_at","last_observed_at","verifiable","model_version","summary","recommendation_status");}
    public Object list(Actor actor,UUID child,String type,int limit,String cursor) {
        if(type!=null&&!TYPES.contains(type))throw ApiException.invalid("INVALID_EVENT_TYPE");
        policy.require(actor,child,"cry".equals(type)?"cry:read":"detection:read");
        int size=Cursors.limit(limit);String context=actor.workspaceId()+":"+child+":"+Objects.toString(type,"all");var position=cursors.decode(context,cursor);
        var params=p("ws",actor.workspaceId(),"child",child,"limit",size+1);String where="";
        if(type!=null){where+=" AND type=:type";params.put("type",type);}
        if(position!=null){where+=" AND (started_at,id)<(:time,:cursorId)";params.put("time",position.time());params.put("cursorId",position.id());}
        var rows=db.list("SELECT * FROM detection_events WHERE workspace_id=:ws AND child_id=:child"+where+" ORDER BY started_at DESC,id DESC LIMIT :limit",params);
        boolean more=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        String next=more?cursors.encode(context,instant(page.getLast(),"started_at"),id(page.getLast(),"id")):null;
        audit.record(actor.workspaceId(),actor.userId(),"event.list","child",child);
        return p("items",page.stream().map(r->withReceipt(actor,r)).toList(),"nextCursor",next);
    }
    private Map<String,Object> withReceipt(Actor actor,Map<String,Object> row) {
        var result=eventView(row);
        result.put("acknowledgedAt",db.one("SELECT acknowledged_at FROM event_acknowledgements WHERE event_id=:e AND membership_id=:m",p("e",row.get("id"),"m",actor.membershipId())).map(r->r.get("acknowledged_at")).orElse(null));
        return result;
    }
    private Map<String,Object> readable(Actor actor,UUID event) {
        var row=db.required("SELECT * FROM detection_events WHERE id=:id AND workspace_id=:ws",p("id",event,"ws",actor.workspaceId()),"EVENT_NOT_ACCESSIBLE");
        policy.require(actor,id(row,"child_id"),"cry".equals(row.get("type"))?"cry:read":"detection:read");return row;
    }
    public Object detail(Actor actor,UUID event) {
        var row=readable(actor,event);var result=withReceipt(actor,row);
        result.put("resolutions",db.list("SELECT id,result,note,resolved_at FROM event_resolutions WHERE event_id=:e AND workspace_id=:ws ORDER BY resolved_at,id",p("e",event,"ws",actor.workspaceId())).stream().map(r->view(r,"id","result","note","resolved_at")).toList());
        result.put("feedback",db.list("SELECT id,response,note,created_at FROM care_feedback WHERE event_id=:e AND membership_id=:m",p("e",event,"m",actor.membershipId())).stream().map(r->view(r,"id","response","note","created_at")).toList());
        audit.record(actor.workspaceId(),actor.userId(),"event.read","event",event);return result;
    }
    public Object recommendations(Actor actor,UUID event) {
        var row=policy.event(actor,event,"cry:read");if(!"cry".equals(row.get("type")))throw ApiException.invalid("CRY_EVENT_REQUIRED");
        var actions=db.list("SELECT * FROM recommendations WHERE event_id=:id ORDER BY priority,id",p("id",event)).stream().map(r->view(r,"id","action_key","title","explanation","instructions","priority","model_version")).toList();
        var causes=db.list("SELECT * FROM cause_candidates WHERE event_id=:id ORDER BY rank,id",p("id",event)).stream().map(r->view(r,"id","cause_key","label","rank","reason")).toList();
        audit.record(actor.workspaceId(),actor.userId(),"recommendation.read","event",event);
        return p("eventId",event,"status",row.get("recommendation_status"),"causes",causes,"actions",actions,"fallbackGuide",List.of("아이의 호흡, 얼굴 가림, 자세를 직접 확인해 주세요.","주변 자극과 기저귀 상태를 확인하고 부드럽게 달래 주세요.","호흡 곤란이나 반응 저하가 보이면 즉시 응급 도움을 요청하세요."),"notice","원인 후보와 행동 안내는 진단이 아니며 아이의 상태를 직접 확인해야 해요.");
    }
    /** Trusted model adapter callback. Published recommendations are immutable snapshots. */
    @Transactional public Object analyze(UUID event,Analysis body) {
        var row=db.required("SELECT * FROM detection_events WHERE id=:id FOR UPDATE",p("id",event),"EVENT_NOT_FOUND");
        if(!"cry".equals(row.get("type")))throw ApiException.invalid("CRY_EVENT_REQUIRED");
        if("ready".equals(row.get("recommendation_status")))throw ApiException.conflict("ANALYSIS_ALREADY_PUBLISHED");
        if(body.status().equals("ready")&&body.actions().isEmpty())throw ApiException.invalid("ACTIONS_REQUIRED");
        if(new HashSet<>(body.actions().stream().map(Action::key).toList()).size()!=body.actions().size())throw ApiException.invalid("DUPLICATE_ACTION");
        if(!body.status().equals("ready")&&(!body.actions().isEmpty()||!body.causes().isEmpty()))throw ApiException.invalid("ANALYSIS_STATUS_MISMATCH");
        int rank=0;for(var cause:body.causes())db.update("INSERT INTO cause_candidates(id,event_id,cause_key,label,rank,reason) VALUES(:id,:e,:key,:label,:rank,:reason)",p("id",UUID.randomUUID(),"e",event,"key",cause.key(),"label",cause.label(),"rank",++rank,"reason",cause.reason()));
        rank=0;for(var action:body.actions())db.update("INSERT INTO recommendations(id,event_id,action_key,title,explanation,instructions,priority,model_version) VALUES(:id,:e,:key,:title,:explanation,cast(:instructions as jsonb),:priority,:model)",p("id",UUID.randomUUID(),"e",event,"key",action.key(),"title",action.title(),"explanation",action.explanation(),"instructions",db.json(action.instructions()),"priority",++rank,"model",body.modelVersion()));
        db.update("UPDATE detection_events SET recommendation_status=:status WHERE id=:id",p("id",event,"status",body.status()));return p("eventId",event,"status",body.status());
    }
    @Transactional public Object feedback(Actor actor,UUID event,Feedback body,String key) {
        var row=policy.event(actor,event,"cry:feedback");if(!"cry".equals(row.get("type")))throw ApiException.invalid("CRY_EVENT_REQUIRED");
        return idem.execute(actor.membershipId(),key,"feedback:"+event,body,()->{
            db.required("SELECT id FROM detection_events WHERE id=:id FOR UPDATE",p("id",event),"EVENT_NOT_FOUND");
            if(db.one("SELECT id FROM care_feedback WHERE event_id=:e AND membership_id=:m",p("e",event,"m",actor.membershipId())).isPresent())throw ApiException.conflict("FEEDBACK_ALREADY_RECORDED");
            var ids=new HashSet<UUID>();for(var attempt:body.attempts()){
                if(!ids.add(attempt.actionId()))throw ApiException.invalid("DUPLICATE_ACTION");
                db.required("SELECT id FROM recommendations WHERE id=:id AND event_id=:e",p("id",attempt.actionId(),"e",event),"ACTION_NOT_IN_EVENT");
            }
            UUID feedback=UUID.randomUUID();
            db.update("INSERT INTO care_feedback(id,event_id,membership_id,workspace_id,response,note,idempotency_key) VALUES(:id,:e,:m,:ws,:response,:note,:key)",p("id",feedback,"e",event,"m",actor.membershipId(),"ws",actor.workspaceId(),"response",body.response(),"note",body.note(),"key",key));
            for(var attempt:body.attempts())db.update("INSERT INTO action_attempts(id,feedback_id,event_id,recommendation_id,action_state,note) VALUES(:id,:f,:e,:r,:state,:note)",p("id",UUID.randomUUID(),"f",feedback,"e",event,"r",attempt.actionId(),"state",attempt.state(),"note",attempt.note()));
            audit.record(actor.workspaceId(),actor.userId(),"feedback.create","event",event);
            return p("id",feedback,"eventId",event,"response",body.response(),"attempts",body.attempts(),"saved",true);
        });
    }
    @Transactional public Object acknowledge(Actor actor,UUID event) {
        policy.event(actor,event,"detection:ack");
        var row=db.required("SELECT * FROM detection_events WHERE id=:e FOR UPDATE",p("e",event),"EVENT_NOT_FOUND");
        db.update("INSERT INTO event_acknowledgements(id,event_id,membership_id,workspace_id) VALUES(:id,:e,:m,:ws) ON CONFLICT(event_id,membership_id) DO NOTHING",p("id",UUID.randomUUID(),"e",event,"m",actor.membershipId(),"ws",actor.workspaceId()));
        if("new".equals(row.get("status"))){db.update("UPDATE detection_events SET status='acknowledged' WHERE id=:id",p("id",event));history(event,"new","acknowledged",actor.userId(),"user.acknowledged");}
        audit.record(actor.workspaceId(),actor.userId(),"event.acknowledge","event",event);
        return view(db.required("SELECT event_id,acknowledged_at FROM event_acknowledgements WHERE event_id=:e AND membership_id=:m",p("e",event,"m",actor.membershipId()),"ACK_NOT_FOUND"),"event_id","acknowledged_at");
    }
    @Transactional public Object resolve(Actor actor,UUID event,Resolution body,String key) {
        policy.event(actor,event,"detection:resolve");
        return idem.execute(actor.membershipId(),key,"resolution:"+event,body,()->{
            var row=db.required("SELECT * FROM detection_events WHERE id=:id FOR UPDATE",p("id",event),"EVENT_NOT_FOUND");
            if("resolved".equals(row.get("status")))throw ApiException.conflict("EVENT_ALREADY_RESOLVED");
            String next=body.result().equals("needs_help")?"checking":"resolved";UUID resolution=UUID.randomUUID();
            db.update("INSERT INTO event_resolutions(id,event_id,membership_id,workspace_id,result,note) VALUES(:id,:e,:m,:ws,:result,:note)",p("id",resolution,"e",event,"m",actor.membershipId(),"ws",actor.workspaceId(),"result",body.result(),"note",body.note()));
            db.update("UPDATE detection_events SET status=:status WHERE id=:id",p("id",event,"status",next));history(event,text(row,"status"),next,actor.userId(),body.result());
            row.put("status",next);outbox.enqueue(row,"detection.updated");audit.record(actor.workspaceId(),actor.userId(),"event.resolve","event",event);
            return p("id",resolution,"eventId",event,"status",next,"result",body.result(),"resolvedAt",next.equals("resolved")?Instant.now():null);
        });
    }
    private void history(UUID event,String from,String to,UUID user,String reason) {db.update("INSERT INTO event_status_history(id,event_id,from_status,to_status,actor_id,reason) VALUES(:id,:e,:from,:to,:user,:reason)",p("id",UUID.randomUUID(),"e",event,"from",from,"to",to,"user",user,"reason",reason));}
}
