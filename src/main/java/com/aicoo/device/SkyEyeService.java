package com.aicoo.device;

import com.aicoo.common.*;
import com.aicoo.monitoring.MonitoringService;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.aicoo.common.Db.*;

/** Allowlisted summaries only: no raw audio, images, embeddings or device paths. */
@Service
public class SkyEyeService {
    public record Camera(boolean available, Instant observedAt,
        @Pattern(regexp="supine|side|prone") String posture,
        @Pattern(regexp="airway_visible|airway_covered") String airway,
        @NotNull @Pattern(regexp="normal|caution|risk|unavailable") String state,
        @DecimalMin("0") @DecimalMax("1") Double bodyConfidence,
        @DecimalMin("0") @DecimalMax("1") Double faceConfidence,
        boolean geometryValid, @Pattern(regexp="monitoring|edge_approach|edge_departure_warning|unavailable") String fallState,
        @DecimalMin("0") @DecimalMax("1000") Double fps) {}
    public record Audio(boolean available, Instant observedAt, boolean cryDetected,
        @DecimalMin("0") @DecimalMax("1") Double babyCryScore,
        @DecimalMin("0") @DecimalMax("1") Double cryingScore) {}
    public record Hint(@NotNull @Pattern(regexp="feed|diaper|hold") String action,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double score) {}
    public record Episode(@NotNull UUID episodeUid,@NotNull Instant startedAt,@NotNull Instant endedAt,
        @Pattern(regexp="feed|diaper|hold|unknown|self") String action) {}
    public record Personal(boolean simulation,
        @NotNull @Pattern(regexp="insufficient_history|collecting_audio|ready|idle|low_confidence") String state,
        @Min(0) @Max(1000000) int nFeedback,@Min(10) @Max(1000000) int requiredFeedback,
        @Min(0) @Max(300) int cryWindows,@Min(10) @Max(300) int requiredCryWindows,boolean soundOn,
        @NotNull @Size(max=2) List<@Valid Hint> recommendations,
        @NotNull @Size(max=5) List<@Valid Episode> recentEpisodes) {}
    public record Status(@NotNull Instant observedAt,@NotNull @Valid Camera video,
        @NotNull @Valid Audio audio,@Valid Personal personalization) {}
    public record Event(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String id,
        @NotBlank @Size(max=128) String episodeKey,
        @NotNull @Pattern(regexp="risk_started|risk_ended|risk_interrupted|status_changed|posture_changed|cry_started|cry_ended|cry_episode_started|cry_episode_ended") String kind,
        @Pattern(regexp="prone|face_covered|edge_departure_warning|cry") String riskType,
        @NotNull Instant observedAt,Instant startedAt,Instant endedAt,UUID episodeUid,
        @Pattern(regexp="quiet|input_gap|segment_limit|service_stopped|interrupted") String endReason) {}
    public record Feedback(@NotNull UUID deviceId,@NotNull UUID episodeUid,
        @NotNull @Pattern(regexp="feed|diaper|hold|unknown|self") String action) {}
    public record Receipt(boolean ok) {}
    private final Db db; private final PairingService pairing; private final Policy policy;
    private final MonitoringService monitoring; private final ObjectMapper mapper;
    public SkyEyeService(Db db,PairingService pairing,Policy policy,MonitoringService monitoring,ObjectMapper mapper) {
        this.db=db;this.pairing=pairing;this.policy=policy;this.monitoring=monitoring;this.mapper=mapper;
    }
    private static boolean fresh(Instant at,Instant now,int seconds) {
        return at!=null&&!at.isBefore(now.minusSeconds(seconds))&&!at.isAfter(now.plusSeconds(5));
    }
    private static boolean confident(Double score){return score!=null&&Double.isFinite(score)&&score>=0.6;}
    private static void validTime(Instant time){if(time!=null&&(time.isBefore(Instant.parse("2020-01-01T00:00:00Z"))||time.isAfter(Instant.now().plusSeconds(5))))throw ApiException.invalid("INVALID_MODEL_TIME");}
    private Status effective(Status source,Instant now) {
        boolean exporter=fresh(source.observedAt(),now,8);
        var v=source.video();var a=source.audio();
        boolean video=exporter&&v.available()&&fresh(v.observedAt(),now,5)&&!v.state().equals("unavailable");
        boolean audio=exporter&&a.available()&&fresh(a.observedAt(),now,6);
        var personal=source.personalization();
        if(!audio||personal!=null&&personal.simulation())personal=null;
        if(personal!=null) {
            boolean ready=personal.state().equals("ready")&&personal.nFeedback()>=Math.max(10,personal.requiredFeedback())&&personal.cryWindows()>=Math.max(10,personal.requiredCryWindows());
            personal=new Personal(false,personal.state(),personal.nFeedback(),personal.requiredFeedback(),personal.cryWindows(),personal.requiredCryWindows(),personal.soundOn(),ready?personal.recommendations():List.of(),personal.recentEpisodes());
        }
        return new Status(source.observedAt(),new Camera(video,v.observedAt(),video?v.posture():null,video?v.airway():null,video?v.state():"unavailable",video?v.bodyConfidence():null,video?v.faceConfidence():null,video&&v.geometryValid(),video&&v.geometryValid()?v.fallState():"unavailable",video?v.fps():null),new Audio(audio,a.observedAt(),audio&&a.cryDetected(),audio?a.babyCryScore():null,audio?a.cryingScore():null),personal);
    }
    private Map<String,String> safety(Status status) {
        var safety=MonitoringService.unknownSafety();var v=status.video();
        if(v.available()) {
            if(confident(v.bodyConfidence())&&v.posture()!=null)safety.put("posture",switch(v.posture()){case "prone"->"immediate";case "side"->"caution";default->"stable";});
            if(confident(v.faceConfidence())&&v.airway()!=null)safety.put("face",v.airway().equals("airway_covered")?"immediate":"stable");
            // No calibrated confidence/object policy is exported for these branches.
            if(v.geometryValid()&&"edge_departure_warning".equals(v.fallState()))safety.put("edge","caution");
        }
        return safety;
    }
    @Transactional public Object status(String token,Status body) {
        var device=pairing.authorize(token);UUID deviceId=id(device,"id");
        validTime(body.observedAt());validTime(body.video().observedAt());validTime(body.audio().observedAt());
        if(body.personalization()!=null)for(var ep:body.personalization().recentEpisodes()){validTime(ep.startedAt());validTime(ep.endedAt());if(ep.endedAt().isBefore(ep.startedAt()))throw ApiException.invalid("INVALID_EPISODE_TIME");}
        var previous=db.one("SELECT observed_at FROM sky_eye_status WHERE device_id=:id",p("id",deviceId));
        if(previous.isPresent()&&body.observedAt().isBefore(instant(previous.get(),"observed_at")))throw ApiException.conflict("STALE_MODEL_STATUS");
        var current=effective(body,Instant.now());
        db.update("INSERT INTO sky_eye_status(device_id,observed_at,body) VALUES(:id,:at,cast(:body as jsonb)) ON CONFLICT(device_id) DO UPDATE SET observed_at=:at,body=cast(:body as jsonb),received_at=now()",p("id",deviceId,"at",body.observedAt(),"body",db.json(current)));
        // Connection/analysis freshness never creates a playable video stream.
        var safety=safety(current);var v=current.video();
        Double confidence=confident(v.bodyConfidence())?v.bodyConfidence():confident(v.faceConfidence())?v.faceConfidence():null;
        monitoring.heartbeat(deviceId,new MonitoringService.Heartbeat(Instant.now(),confidence==null?"unverifiable":"live",safety,confidence,v.observedAt()));
        return p("accepted",true);
    }
    @Transactional public Object event(String token,Event body) {
        var device=pairing.authorize(token);validTime(body.observedAt());validTime(body.startedAt());validTime(body.endedAt());
        if(body.startedAt()!=null&&body.endedAt()!=null&&body.endedAt().isBefore(body.startedAt()))throw ApiException.invalid("INVALID_EPISODE_TIME");
        var args=p("device",device.get("id"),"event",body.id(),"at",body.observedAt(),"body",db.json(body));
        var existing=db.one("SELECT body FROM sky_eye_events WHERE device_id=:device AND event_id=:event",args);
        if(existing.isPresent()) {
            if(!mapper.valueToTree(existing.get().get("body")).equals(mapper.valueToTree(body)))throw ApiException.conflict("MODEL_EVENT_CONFLICT");
        } else db.update("INSERT INTO sky_eye_events(device_id,event_id,observed_at,body) VALUES(:device,:event,:at,cast(:body as jsonb))",args);
        // Historical import is a record, never an outbox/push notification.
        return p("accepted",true,"duplicate",existing.isPresent());
    }
    public Object snapshot(Actor actor,UUID child) {
        policy.require(actor,child,"monitor:live");policy.require(actor,child,"cry:read");policy.require(actor,child,"detection:read");
        var devices=db.list("SELECT s.*,d.display_name FROM sky_eye_status s JOIN devices d ON d.id=s.device_id WHERE d.workspace_id=:ws AND d.child_id=:child AND d.revoked_at IS NULL ORDER BY s.received_at DESC LIMIT 10",p("ws",actor.workspaceId(),"child",child));
        var items=new ArrayList<Map<String,Object>>();Instant now=Instant.now();
        for(var row:devices) {
            UUID device=id(row,"device_id");var status=effective(mapper.convertValue(row.get("body"),Status.class),now);
            var events=db.list("SELECT body,received_at FROM sky_eye_events WHERE device_id=:device ORDER BY observed_at DESC,event_id LIMIT 30",p("device",device));
            var eventViews=events.stream().map(e->{var event=mapper.convertValue(e.get("body"),Event.class);return p("event",event,"recent",fresh(event.observedAt(),now,30)&&fresh(instant(e,"received_at"),now,30));}).toList();
            var commands=db.list("SELECT id,episode_uid,action,CASE WHEN status='queued' AND expires_at<now() THEN 'expired' ELSE status END AS status FROM sky_eye_commands WHERE device_id=:device ORDER BY created_at DESC LIMIT 20",p("device",device)).stream().map(c->view(c,"id","episode_uid","action","status")).toList();
            items.add(p("deviceId",device,"name",row.get("display_name"),"connected",fresh(instant(row,"received_at"),now,15),"status",status,"safety",safety(status),"events",eventViews,"commands",commands));
        }
        return p("childId",child,"items",items,"serverTime",now);
    }
    @Transactional public Object feedback(Actor actor,UUID child,Feedback body) {
        policy.verified(actor);policy.require(actor,child,"cry:feedback");
        db.required("SELECT id FROM devices WHERE id=:device AND workspace_id=:ws AND child_id=:child AND revoked_at IS NULL FOR UPDATE",p("device",body.deviceId(),"ws",actor.workspaceId(),"child",child),"DEVICE_NOT_ACCESSIBLE");
        var source=db.required("SELECT body FROM sky_eye_status WHERE device_id=:device",p("device",body.deviceId()),"MODEL_STATUS_NOT_FOUND");
        var status=mapper.convertValue(source.get("body"),Status.class);var personal=status.personalization();
        boolean inRecent=personal!=null&&!personal.simulation()&&personal.recentEpisodes().stream().anyMatch(e->e.episodeUid().equals(body.episodeUid()));
        boolean inEvents=!db.list("SELECT event_id FROM sky_eye_events WHERE device_id=:device AND body->>'kind'='cry_episode_ended' AND body->>'endReason'='quiet' AND body->>'episodeUid'=:episode LIMIT 1",p("device",body.deviceId(),"episode",body.episodeUid().toString())).isEmpty();
        if(!inRecent&&!inEvents)throw ApiException.invalid("COMPLETED_EPISODE_REQUIRED");
        var old=db.one("SELECT * FROM sky_eye_commands WHERE device_id=:device AND episode_uid=:episode",p("device",body.deviceId(),"episode",body.episodeUid()));
        if(old.isPresent()) {
            if(!body.action().equals(old.get().get("action")))throw ApiException.conflict("FEEDBACK_ALREADY_RECORDED");
            return commandView(old.get());
        }
        UUID command=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);
        db.update("INSERT INTO sky_eye_commands(id,device_id,episode_uid,action,expires_at) VALUES(:id,:device,:episode,:action,:expires)",p("id",command,"device",body.deviceId(),"episode",body.episodeUid(),"action",body.action(),"expires",expires));
        return p("id",command,"status","queued");
    }
    private Map<String,Object> commandView(Map<String,Object> row) {
        String status=text(row,"status");if(status.equals("queued")&&instant(row,"expires_at").isBefore(Instant.now()))status="expired";
        return p("id",row.get("id"),"status",status);
    }
    @Transactional public Object commands(String token) {
        var device=pairing.authorize(token);
        return p("items",db.list("SELECT id,episode_uid,action,created_at,expires_at FROM sky_eye_commands WHERE device_id=:device AND status='queued' AND expires_at>now() ORDER BY created_at LIMIT 20",p("device",device.get("id"))).stream().map(c->view(c,"id","episode_uid","action","created_at","expires_at")).toList());
    }
    @Transactional public Object receipt(String token,UUID command,Receipt body) {
        var device=pairing.authorize(token);var row=db.required("SELECT * FROM sky_eye_commands WHERE id=:id AND device_id=:device FOR UPDATE",p("id",command,"device",device.get("id")),"COMMAND_NOT_FOUND");
        String status=body.ok()?"applied":"failed";
        if(!text(row,"status").equals("queued")&&!text(row,"status").equals(status))throw ApiException.conflict("RECEIPT_CONFLICT");
        db.update("UPDATE sky_eye_commands SET status=:status,completed_at=coalesce(completed_at,now()) WHERE id=:id",p("status",status,"id",command));
        return p("id",command,"status",status);
    }
}
