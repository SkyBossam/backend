package com.aicoo.care;

import com.aicoo.common.*;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.aicoo.common.Db.*;

/** Durable child-scoped records. Live camera availability is unrelated to history access. */
@Service
public class CareHistoryService {
    public record RoutineWrite(@NotNull UUID id,@Positive Long version,
        @NotBlank @Pattern(regexp="sleep|wake|play|soothe|custom") String type,
        @NotBlank @Size(max=100) String title,@Size(max=1000) String note,
        @NotNull Instant scheduledAt,Instant endedAt,
        @NotBlank @Pattern(regexp="todo|in_progress|done") String status) {}
    public record SleepWrite(@NotNull UUID id,@Positive Long version,@NotNull Instant startedAt,@NotNull Instant endedAt) {}
    public record Delete(@NotNull UUID id,@NotNull @Positive Long version) {}
    public record FeedbackWrite(@NotNull UUID eventId,@Positive Long version,
        @NotNull @Size(min=1,max=10) List<@NotBlank @Size(max=206) String> actions,
        @NotNull @Size(max=1000) String note,
        @NotBlank @Pattern(regexp="stopped|reduced|continues|unknown") String response) {}
    public record Changes(@NotNull @Size(max=50) List<@Valid RoutineWrite> routines,
        @NotNull @Size(max=50) List<@Valid Delete> deletedRoutines,
        @NotNull @Size(max=50) List<@Valid SleepWrite> sleepRecords,
        @NotNull @Size(max=50) List<@Valid Delete> deletedSleepRecords,
        @NotNull @Size(max=50) List<@Valid FeedbackWrite> feedback) {}
    private final Db db;
    private final Policy policy;
    private final Cursors cursors;
    private final Idempotency idem;
    private final Audit audit;
    public CareHistoryService(Db db,Policy policy,Cursors cursors,Idempotency idem,Audit audit) {
        this.db=db;this.policy=policy;this.cursors=cursors;this.idem=idem;this.audit=audit;
    }

    public Object list(Actor actor,UUID child,String kind,int limit,String cursor) {
        String capability=switch(kind) {
            case "cries","feedback" -> "cry:read";
            case "routines","sleepRecords" -> "routine:read";
            default -> throw ApiException.invalid("INVALID_HISTORY_KIND");
        };
        policy.require(actor,child,"child:read");
        // A reader without one of the optional scopes may still view the others.
        if(!policy.allowed(actor,child,capability))return p("items",List.of(),"nextCursor",null);
        int size=Cursors.limit(limit);
        String context="care-history:"+actor.membershipId()+":"+actor.workspaceId()+":"+child+":"+kind;
        var position=cursors.decode(context,cursor);
        var params=p("ws",actor.workspaceId(),"child",child,"member",actor.membershipId(),"limit",size+1);
        String source=switch(kind) {
            case "cries" -> "SELECT e.*,g.closed_at AS alert_closed_at,(SELECT count(*) FROM detection_events x WHERE x.cry_alert_id=e.cry_alert_id AND NOT x.cry_retired) AS alert_occurrences FROM detection_events e LEFT JOIN cry_alert_groups g ON g.id=e.cry_alert_id WHERE e.workspace_id=:ws AND e.child_id=:child AND e.type='cry' AND NOT e.cry_retired";
            case "routines" -> "SELECT *,coalesce(scheduled_at,created_at) AS record_time FROM routines WHERE workspace_id=:ws AND child_id=:child AND deleted_at IS NULL";
            case "sleepRecords" -> "SELECT * FROM sleep_records WHERE workspace_id=:ws AND child_id=:child AND deleted_at IS NULL";
            default -> "SELECT f.*,e.child_id FROM care_feedback f JOIN detection_events e ON e.id=f.event_id WHERE f.workspace_id=:ws AND e.child_id=:child AND e.type='cry' AND f.membership_id=:member";
        };
        String time=switch(kind){case "routines"->"record_time";case "feedback"->"created_at";default->"started_at";};
        String where="";
        if(position!=null){where=" WHERE ("+time+",id)<(:time,:cursorId)";params.put("time",position.time());params.put("cursorId",position.id());}
        var rows=db.list("SELECT * FROM ("+source+") records"+where+" ORDER BY "+time+" DESC,id DESC LIMIT :limit",params);
        var page=rows.subList(0,Math.min(size,rows.size()));
        String next=rows.size()>size?cursors.encode(context,instant(page.getLast(),time),id(page.getLast(),"id")):null;
        return p("items",page.stream().map(r->recordView(kind,r)).toList(),"nextCursor",next);
    }

    private Map<String,Object> recordView(String kind,Map<String,Object> row) {
        return switch(kind) {
            case "cries" -> cryView(row);
            case "routines" -> view(row,"id","child_id","type","title","note","scheduled_at","ended_at","status","version");
            case "sleepRecords" -> view(row,"id","child_id","started_at","ended_at","version");
            default -> view(row,"event_id","child_id","actions","note","response","created_at","updated_at","version","history");
        };
    }

    private Map<String,Object> cryView(Map<String,Object> row) {
        var causes=db.list("SELECT cause_key,label,reason FROM cause_candidates WHERE event_id=:id ORDER BY rank,id",p("id",row.get("id"))).stream().map(r->view(r,"cause_key","label","reason")).toList();
        var recommendations=db.list("SELECT action_key,title,explanation,instructions FROM recommendations WHERE event_id=:id ORDER BY priority,id",p("id",row.get("id"))).stream().map(r->view(r,"action_key","title","explanation","instructions")).toList();
        boolean ended=row.get("ended_at")!=null;
        boolean interrupted="interrupted".equals(row.get("cry_end_reason"))||!ended&&instant(row,"last_observed_at").isBefore(Instant.now().minusSeconds(8));
        String state=ended?(Set.of("input_gap","service_stopped","interrupted").contains(Objects.toString(row.get("cry_end_reason"),""))?"interrupted":"ended"):interrupted?"interrupted":"crying";
        return p("id",row.get("id"),"childId",row.get("child_id"),"sourceId",row.get("source_id"),"lastObservedAt",row.get("last_observed_at"),"startedAt",row.get("started_at"),"endedAt",row.get("ended_at"),
            "durationSec",ended&&!state.equals("interrupted")?Duration.between(instant(row,"started_at"),instant(row,"ended_at")).getSeconds():null,
            "observationState",state,"alertId",row.get("cry_alert_id"),"alertClosed",row.get("alert_closed_at")!=null,"alertOccurrences",row.get("alert_occurrences"),
            "cause",causes.isEmpty()?"원인 미분류":causes.getFirst().get("label"),"analysisAvailable",!causes.isEmpty(),"audioAnalysis",row.get("cry_analysis"),"causes",causes,"recommendations",recommendations);
    }

    @Transactional
    public Map<String,Object> save(Actor actor,UUID child,Changes changes,String key) {
        var link=policy.require(actor,child,"child:read");
        boolean daily=!changes.routines().isEmpty()||!changes.deletedRoutines().isEmpty()||!changes.sleepRecords().isEmpty()||!changes.deletedSleepRecords().isEmpty();
        if(daily){policy.require(actor,child,"routine:read");policy.require(actor,child,"routine:write");}
        if(!changes.feedback().isEmpty()){policy.require(actor,child,"cry:read");policy.require(actor,child,"cry:feedback");}
        return idem.execute(actor.membershipId(),key,"care-history.save:"+child,changes,()-> {
            // Serializes a batch with other journal writers for this child. Version checks
            // also detect changes made through the existing routine API.
            db.list("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))",p("scope","care-history:"+actor.workspaceId()+":"+child));
            var savedRoutines=new ArrayList<Map<String,Object>>();
            var savedSleep=new ArrayList<Map<String,Object>>();
            var savedFeedback=new ArrayList<Map<String,Object>>();
            Set<UUID> routineIds=new HashSet<>(),sleepIds=new HashSet<>(),events=new HashSet<>();
            for(var r:changes.routines()) { unique(routineIds,r.id()); savedRoutines.add(saveRoutine(actor,child,link,r)); }
            for(var r:changes.deletedRoutines()) { unique(routineIds,r.id()); delete(actor,child,"routines",r); }
            for(var r:changes.sleepRecords()) { unique(sleepIds,r.id()); savedSleep.add(saveSleep(actor,child,link,r)); }
            for(var r:changes.deletedSleepRecords()) { unique(sleepIds,r.id()); delete(actor,child,"sleep_records",r); }
            for(var f:changes.feedback()) { unique(events,f.eventId()); savedFeedback.add(saveFeedback(actor,child,f)); }
            audit.record(actor.workspaceId(),actor.userId(),"care-history.save","child",child);
            return p("routines",savedRoutines,"sleepRecords",savedSleep,"feedback",savedFeedback);
        });
    }
    private void unique(Set<UUID> ids,UUID id){if(!ids.add(id))throw ApiException.invalid("DUPLICATE_RECORD_CHANGE");}
    private void checkVersion(String table,Actor actor,UUID child,UUID id,Long version) {
        var row=db.one("SELECT version,workspace_id,child_id,deleted_at FROM "+table+" WHERE id=:id FOR UPDATE",p("id",id));
        if(version==null) {
            if(row.isPresent())throw ApiException.conflict("CARE_RECORD_CHANGED");
        } else if(row.isEmpty()||!actor.workspaceId().equals(row.get().get("workspace_id"))||!child.equals(row.get().get("child_id"))||row.get().get("deleted_at")!=null||((Number)row.get().get("version")).longValue()!=version)
            throw ApiException.conflict("CARE_RECORD_CHANGED");
    }
    private Map<String,Object> saveRoutine(Actor actor,UUID child,Map<String,Object> link,RoutineWrite r) {
        if(r.endedAt()!=null&&r.endedAt().isBefore(r.scheduledAt()))throw ApiException.invalid("INVALID_TIME_RANGE");
        checkVersion("routines",actor,child,r.id(),r.version());
        var params=p("id",r.id(),"ws",actor.workspaceId(),"child",child,"wc",link.get("id"),"member",actor.membershipId(),
            "title",r.title().trim(),"type",r.type(),"note",r.note(),"start",r.scheduledAt(),"end",r.endedAt(),"status",r.status());
        if(r.version()==null)db.update("INSERT INTO routines(id,workspace_id,workspace_child_id,child_id,type,title,note,scheduled_at,ended_at,timezone,status,completed_at,created_by) VALUES(:id,:ws,:wc,:child,:type,:title,:note,:start,:end,'Asia/Seoul',:status,CASE WHEN :status='done' THEN now() ELSE NULL END,:member)",params);
        else db.update("UPDATE routines SET title=:title,type=:type,note=:note,scheduled_at=:start,ended_at=:end,status=:status,timezone='Asia/Seoul',completed_at=CASE WHEN :status='done' THEN coalesce(completed_at,now()) ELSE NULL END,version=version+1,updated_at=now() WHERE id=:id",params);
        return recordView("routines",db.required("SELECT * FROM routines WHERE id=:id",params,"ROUTINE_NOT_ACCESSIBLE"));
    }
    private Map<String,Object> saveSleep(Actor actor,UUID child,Map<String,Object> link,SleepWrite r) {
        if(!r.endedAt().isAfter(r.startedAt())||r.endedAt().isAfter(r.startedAt().plus(Duration.ofDays(7)))||r.endedAt().isAfter(Instant.now().plusSeconds(300)))throw ApiException.invalid("INVALID_SLEEP_TIME");
        checkVersion("sleep_records",actor,child,r.id(),r.version());
        var params=p("id",r.id(),"ws",actor.workspaceId(),"child",child,"wc",link.get("id"),"member",actor.membershipId(),"start",r.startedAt(),"end",r.endedAt());
        if(r.version()==null)db.update("INSERT INTO sleep_records(id,workspace_id,workspace_child_id,child_id,started_at,ended_at,created_by) VALUES(:id,:ws,:wc,:child,:start,:end,:member)",params);
        else db.update("UPDATE sleep_records SET started_at=:start,ended_at=:end,version=version+1,updated_at=now() WHERE id=:id",params);
        return recordView("sleepRecords",db.required("SELECT * FROM sleep_records WHERE id=:id",params,"SLEEP_RECORD_NOT_ACCESSIBLE"));
    }
    private void delete(Actor actor,UUID child,String table,Delete r) {
        checkVersion(table,actor,child,r.id(),r.version());
        db.update("UPDATE "+table+" SET deleted_at=now(),updated_at=now(),version=version+1 WHERE id=:id",p("id",r.id()));
    }
    private Map<String,Object> saveFeedback(Actor actor,UUID child,FeedbackWrite f) {
        var allowed=Set.of("sleep","hug","noise","posture","diaper","feed","none");
        if(f.actions().stream().anyMatch(a->!allowed.contains(a)&&!(a.startsWith("other:")&&!a.substring(6).isBlank()))||new HashSet<>(f.actions()).size()!=f.actions().size()||(f.actions().contains("none")&&f.actions().size()>1))throw ApiException.invalid("INVALID_CARE_ACTIONS");
        db.required("SELECT id FROM detection_events WHERE id=:id AND workspace_id=:ws AND child_id=:child AND type='cry' FOR UPDATE",p("id",f.eventId(),"ws",actor.workspaceId(),"child",child),"EVENT_NOT_ACCESSIBLE");
        var params=p("event",f.eventId(),"member",actor.membershipId(),"ws",actor.workspaceId(),"child",child);
        var previous=db.one("SELECT *, :child AS child_id FROM care_feedback WHERE event_id=:event AND membership_id=:member",params);
        if(previous.isPresent()) {
            var old=previous.get();
            if(f.version()==null) {
                if(!Objects.equals(old.get("actions"),f.actions())||!Objects.equals(old.get("note"),f.note())||!Objects.equals(old.get("response"),f.response()))throw ApiException.conflict("FEEDBACK_ALREADY_RECORDED");
                return recordView("feedback",old);
            }
            if(((Number)old.get("version")).longValue()!=f.version())throw ApiException.conflict("CARE_RECORD_CHANGED");
            params.putAll(p("actions",db.json(f.actions()),"note",f.note(),"response",f.response()));
            db.update("UPDATE care_feedback SET history=history || jsonb_build_array(jsonb_build_object('actions',actions,'note',note,'response',response,'createdAt',updated_at)), actions=cast(:actions as jsonb),note=:note,response=:response,version=version+1,updated_at=now() WHERE event_id=:event AND membership_id=:member",params);
            return recordView("feedback",db.required("SELECT *, :child AS child_id FROM care_feedback WHERE event_id=:event AND membership_id=:member",params,"FEEDBACK_NOT_FOUND"));
        }
        if(f.version()!=null)throw ApiException.conflict("CARE_RECORD_CHANGED");
        params.putAll(p("id",UUID.randomUUID(),"actions",db.json(f.actions()),"note",f.note(),"response",f.response(),"key","care:"+f.eventId()));
        db.update("INSERT INTO care_feedback(id,event_id,membership_id,workspace_id,response,note,idempotency_key,actions) VALUES(:id,:event,:member,:ws,:response,:note,:key,cast(:actions as jsonb))",params);
        return recordView("feedback",db.required("SELECT *, :child AS child_id FROM care_feedback WHERE id=:id",params,"FEEDBACK_NOT_FOUND"));
    }
}
