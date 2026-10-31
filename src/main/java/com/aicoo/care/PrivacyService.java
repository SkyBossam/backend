package com.aicoo.care;

import com.aicoo.common.*;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class PrivacyService {
    public record Request(@NotBlank @Pattern(regexp="export|delete")String type){}
    private final Db db;private final Policy policy;private final CareService care;private final Audit audit;
    public PrivacyService(Db db,Policy policy,CareService care,Audit audit){this.db=db;this.policy=policy;this.care=care;this.audit=audit;}
    @Transactional public Object request(Actor actor,Request body){policy.verified(actor);UUID id=UUID.randomUUID();db.update("INSERT INTO privacy_requests(id,user_id,workspace_id,type) VALUES(:id,:user,:ws,:type)",p("id",id,"user",actor.userId(),"ws",actor.workspaceId(),"type",body.type()));audit.record(actor.workspaceId(),actor.userId(),"privacy.request","privacy_request",id);return p("id",id,"type",body.type(),"status","pending","message","요청이 접수되었어요. 데이터 처리 담당자가 보존 정책과 접근 범위를 확인한 뒤 처리합니다.");}
    public Object requests(Actor actor){return p("items",db.list("SELECT id,type,status,created_at FROM privacy_requests WHERE user_id=:user AND workspace_id=:ws ORDER BY created_at DESC LIMIT 100",p("user",actor.userId(),"ws",actor.workspaceId())).stream().map(r->view(r,"id","type","status","created_at")).toList());}
    /** Export metadata and only currently authorized records, never credentials, raw media or other tenants. */
    public Object export(Actor actor){
        policy.verified(actor);var children=new ArrayList<Map<String,Object>>();
        for(UUID child:policy.accessibleChildren(actor,"child:read")){
            var row=new LinkedHashMap<>(care.child(actor,child));var params=p("ws",actor.workspaceId(),"child",child);boolean all=policy.allowed(actor,child,"detection:read"),cry=policy.allowed(actor,child,"cry:read");
            row.put("events",all||cry?db.list("SELECT id,type,severity,status,started_at,ended_at,summary FROM detection_events WHERE workspace_id=:ws AND child_id=:child"+(all?"":" AND type='cry'")+" ORDER BY started_at,id LIMIT 10001",params).stream().map(r->view(r,"id","type","severity","status","started_at","ended_at","summary")).toList():List.of());
            row.put("routines",policy.allowed(actor,child,"routine:read")?care.routines(actor,child,null):null);
            if(((List<?>)row.get("events")).size()>10000)throw new ApiException(413,"EXPORT_REQUIRES_REQUEST","큰 내보내기는 개인정보 요청 API로 신청해 주세요.");children.add(row);
        }
        audit.record(actor.workspaceId(),actor.userId(),"privacy.export","workspace",actor.workspaceId());return p("workspaceId",actor.workspaceId(),"exportedAt",Instant.now(),"children",children,"scope","현재 접근 가능한 아이 정보, 이벤트 및 최대 500개 루틴. 전체 내보내기는 export 요청을 이용하세요.");
    }
}
