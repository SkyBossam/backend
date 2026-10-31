package com.aicoo.workspace;

import com.aicoo.common.*;
import com.aicoo.detection.EventService;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.aicoo.common.Db.*;

/** Explicit, read-only cross-workspace access. Standard endpoints never mix workspace records. */
@Service
public class SharedDataService {
    private final Db db;private final Policy policy;private final Cursors cursors;private final EventService events;private final Audit audit;
    public SharedDataService(Db db,Policy policy,Cursors cursors,EventService events,Audit audit){this.db=db;this.policy=policy;this.cursors=cursors;this.events=events;this.audit=audit;}
    private Map<String,Object> share(Actor actor,UUID id,String capability){
        var share=db.required("SELECT s.* FROM data_share_grants s JOIN workspaces w ON w.id=s.from_workspace_id WHERE s.id=:id AND s.to_workspace_id=:ws AND s.revoked_at IS NULL AND s.expires_at>now() AND w.status='active'",p("id",id,"ws",actor.workspaceId()),"SHARE_NOT_ACCESSIBLE");
        policy.require(actor,id(share,"child_id"),capability);
        if(!strings(share,"scopes").contains(capability))throw ApiException.forbidden("CAPABILITY_REQUIRED");return share;
    }
    public Object list(Actor actor,UUID id,String type,int limit,String cursor){
        if(type!=null&&!EventService.TYPES.contains(type))throw ApiException.invalid("INVALID_EVENT_TYPE");
        var share=share(actor,id,"cry".equals(type)?"cry:read":"detection:read");int size=Cursors.limit(limit);String context="share:"+actor.workspaceId()+":"+id+":"+Objects.toString(type,"all");var position=cursors.decode(context,cursor);
        var params=p("ws",share.get("from_workspace_id"),"child",share.get("child_id"),"limit",size+1);String where="";
        if(type!=null){where+=" AND type=:type";params.put("type",type);}if(position!=null){where+=" AND (started_at,id)<(:at,:id)";params.put("at",position.time());params.put("id",position.id());}
        var rows=db.list("SELECT * FROM detection_events WHERE workspace_id=:ws AND child_id=:child"+where+" ORDER BY started_at DESC,id DESC LIMIT :limit",params);boolean more=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        audit.record(actor.workspaceId(),actor.userId(),"share.events.read","share",id);
        return p("shareGrantId",id,"sourceWorkspaceId",share.get("from_workspace_id"),"items",page.stream().map(events::eventView).toList(),"nextCursor",more?cursors.encode(context,instant(page.getLast(),"started_at"),id(page.getLast(),"id")):null);
    }
}
