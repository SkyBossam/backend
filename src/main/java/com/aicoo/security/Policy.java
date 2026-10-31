package com.aicoo.security;

import com.aicoo.common.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service public class Policy {
    public static final Set<String> CAPABILITIES=Set.of("child:read","child:manage","routine:read","routine:write","cry:read","cry:feedback","monitor:live","detection:read","detection:ack","detection:resolve","notification:receive");
    public record Actor(UUID userId,UUID sessionId,UUID membershipId,UUID workspaceId,String role){}
    private final Db db;public Policy(Db db){this.db=db;}
    public Actor actor(Jwt jwt,UUID workspace){
        UUID user=UUID.fromString(jwt.getSubject()),session=UUID.fromString(jwt.getClaimAsString("sid"));
        requireSession(user,session);
        var member=db.required("SELECT m.* FROM memberships m JOIN workspaces w ON w.id=m.workspace_id WHERE m.user_id=:user AND m.workspace_id=:ws AND m.status='active' AND w.status='active'",p("user",user,"ws",workspace),"WORKSPACE_NOT_ACCESSIBLE");
        return new Actor(user,session,id(member,"id"),workspace,text(member,"role"));
    }
    public void requireSession(UUID user,UUID session){if(db.one("SELECT s.id FROM user_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=:s AND s.user_id=:u AND s.revoked_at IS NULL AND s.expires_at>now() AND u.status='active'",p("s",session,"u",user)).isEmpty())throw ApiException.unauthorized();}
    public Actor memberActor(UUID membership){var m=db.required("SELECT m.* FROM memberships m JOIN users u ON u.id=m.user_id JOIN workspaces w ON w.id=m.workspace_id WHERE m.id=:id AND m.status='active' AND u.status='active' AND w.status='active'",p("id",membership),"MEMBER_NOT_ACCESSIBLE");return new Actor(id(m,"user_id"),null,membership,id(m,"workspace_id"),text(m,"role"));}
    public Map<String,Object> link(Actor actor,UUID child){
        var link=db.required("SELECT * FROM workspace_children WHERE workspace_id=:ws AND child_id=:child AND link_status='active'",p("ws",actor.workspaceId(),"child",child),"CHILD_NOT_ACCESSIBLE");
        if(link.get("share_grant_id")!=null)db.required("SELECT id FROM data_share_grants WHERE id=:id AND to_workspace_id=:ws AND child_id=:child AND revoked_at IS NULL AND expires_at>now()",p("id",link.get("share_grant_id"),"ws",actor.workspaceId(),"child",child),"CHILD_NOT_ACCESSIBLE");return link;
    }
    public Set<String> capabilities(Actor actor,UUID child){
        var link=link(actor,child);Set<String> result=new HashSet<>();
        for(var grant:db.list("SELECT capabilities FROM child_access_grants WHERE workspace_id=:ws AND workspace_child_id=:wc AND membership_id=:m AND revoked_at IS NULL AND valid_from<=now() AND (valid_until IS NULL OR valid_until>now())",p("ws",actor.workspaceId(),"wc",link.get("id"),"m",actor.membershipId())))result.addAll(strings(grant,"capabilities"));
        if(link.get("share_grant_id")!=null){var share=db.required("SELECT scopes FROM data_share_grants WHERE id=:id AND revoked_at IS NULL AND expires_at>now()",p("id",link.get("share_grant_id")),"CHILD_NOT_ACCESSIBLE");result.retainAll(strings(share,"scopes"));}
        result.retainAll(roleCapabilities(actor.role()));return result;
    }
    public Map<String,Object> require(Actor actor,UUID child,String capability){Set<String> granted=capabilities(actor,child);if(granted.isEmpty())throw ApiException.notFound("CHILD_NOT_ACCESSIBLE");if(!granted.contains(capability))throw ApiException.forbidden("CAPABILITY_REQUIRED");return link(actor,child);}
    public boolean allowed(Actor actor,UUID child,String capability){try{return capabilities(actor,child).contains(capability);}catch(ApiException e){return false;}}
    public void manager(Actor actor){if(!Set.of("owner","admin").contains(actor.role()))throw ApiException.forbidden("WORKSPACE_MANAGER_REQUIRED");}
    public void verified(Actor actor){if(!Boolean.TRUE.equals(db.required("SELECT email_verified FROM users WHERE id=:id",p("id",actor.userId()),"USER_NOT_FOUND").get("email_verified")))throw ApiException.forbidden("EMAIL_VERIFICATION_REQUIRED");}
    public Set<String> delegatable(Actor actor,UUID child){var link=link(actor,child);Set<String> caps;
        if(Set.of("owner","admin").contains(actor.role())){caps=new HashSet<>(CAPABILITIES);if(link.get("share_grant_id")!=null)caps.retainAll(strings(db.required("SELECT scopes FROM data_share_grants WHERE id=:id AND revoked_at IS NULL AND expires_at>now()",p("id",link.get("share_grant_id")),"CHILD_NOT_ACCESSIBLE"),"scopes"));}
        else{require(actor,child,"child:manage");caps=capabilities(actor,child);}return caps;
    }
    public void validateCaps(Actor actor,UUID child,String role,List<String> caps){if(caps==null||caps.isEmpty()||!CAPABILITIES.containsAll(caps)||!roleCapabilities(role).containsAll(caps)||!delegatable(actor,child).containsAll(caps))throw ApiException.forbidden("GRANT_EXCEEDS_AUTHORITY");}
    public static Set<String> roleCapabilities(String role){if(role.equals("viewer"))return Set.of("child:read","routine:read","cry:read","monitor:live","detection:read","notification:receive");if(Set.of("sitter","staff").contains(role)){var caps=new HashSet<>(CAPABILITIES);caps.remove("child:manage");return caps;}return CAPABILITIES;}
    public Map<String,Object> event(Actor actor,UUID event,String capability){var row=db.required("SELECT * FROM detection_events WHERE id=:id AND workspace_id=:ws",p("id",event,"ws",actor.workspaceId()),"EVENT_NOT_ACCESSIBLE");require(actor,id(row,"child_id"),capability);return row;}
    public List<UUID> accessibleChildren(Actor actor,String capability){return db.list("SELECT child_id FROM workspace_children WHERE workspace_id=:ws AND link_status='active'",p("ws",actor.workspaceId())).stream().map(r->id(r,"child_id")).filter(child->allowed(actor,child,capability)).toList();}
    public void revokeStreams(UUID membership,UUID child){db.update("UPDATE stream_access_tokens SET revoked_at=now() WHERE membership_id=:m AND child_id=:child AND revoked_at IS NULL",p("m",membership,"child",child));}
}
