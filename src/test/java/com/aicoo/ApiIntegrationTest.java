package com.aicoo;

import com.aicoo.common.*;
import com.aicoo.notification.*;
import com.aicoo.security.*;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.aicoo.common.Db.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"app.outbox-enabled=false","app.jwt-secret=integration-test-jwt-key-at-least-32-characters","app.worker-key=integration-worker-secret","app.mail-mode=local","app.media-gateway-url=https://media.example.test"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ApiIntegrationTest {
    private static final EmbeddedPostgres PG;
    static {try{PG=LocalPostgres.builder().start();}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Db db;@Autowired OutboxWorker outbox;@Autowired Policy policy;@Autowired NotificationService notifications;@Autowired JwtDecoder decoder;
    record User(String token,String refresh,UUID workspace,UUID member,UUID user,String email){}
    private int emails;
    @BeforeEach void reset(){db.update("TRUNCATE users,rate_limits RESTART IDENTITY CASCADE",Map.of());emails=0;}
    private JsonNode response(MockHttpServletRequestBuilder request,int status)throws Exception{
        var result=mvc.perform(request).andReturn();String content=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body=content.isBlank()?json.createObjectNode():json.readTree(content);
        // Do not print full responses: auth responses contain secrets.
        assertThat(result.getResponse().getStatus()).withFailMessage("Expected %s, got %s (%s)",status,result.getResponse().getStatus(),body.path("error").path("code").asText()).isEqualTo(status);return body;
    }
    private JsonNode call(String method,String path,User user,Object body,String key,int status)throws Exception{
        var req=request(HttpMethod.valueOf(method),"/api/v1"+path).contentType("application/json");
        if(user!=null)req.header("Authorization","Bearer "+user.token()).header("X-Workspace-Id",user.workspace());if(key!=null)req.header("Idempotency-Key",key);if(body!=null)req.content(json.writeValueAsBytes(body));return response(req,status);
    }
    private JsonNode call(String method,String path,User user,Object body,int status)throws Exception{return call(method,path,user,body,null,status);}
    private JsonNode worker(String path,Object body,int status)throws Exception{return response(post("/api/v1/internal"+path).header("X-Worker-Key","integration-worker-secret").contentType("application/json").content(json.writeValueAsBytes(body)),status);}
    private UUID uuid(JsonNode node,String field){return UUID.fromString(node.path(field).asText());}
    private User user()throws Exception{
        String email="test"+(++emails)+"@example.test";var signed=call("POST","/auth/signup",null,p("email",email,"password","correct-password-123"),201);
        call("POST","/auth/verify-email",null,p("token",signed.path("verificationToken").asText()),200);
        return new User(signed.path("accessToken").asText(),signed.path("refreshToken").asText(),uuid(signed,"workspaceId"),uuid(signed,"membershipId"),uuid(signed,"userId"),email);
    }
    private User workspace(User user,UUID workspace,UUID member){return new User(user.token(),user.refresh(),workspace,member,user.user(),user.email());}
    private JsonNode child(User user)throws Exception{return call("POST","/children",user,p("displayName","지안","birthDate","2026-01-15"),201);}
    private UUID camera(User user,UUID child)throws Exception{return uuid(worker("/devices",p("workspaceId",user.workspace(),"childId",child,"type","camera"),200),"id");}
    private JsonNode detection(User user,UUID child,UUID camera,String type,String severity,Instant at)throws Exception{
        return worker("/detections",p("workspaceId",user.workspace(),"childId",child,"sourceId",camera,"sourceEventId",UUID.randomUUID(),"type",type,"severity",severity,"startedAt",at,"endedAt",at.plusSeconds(1),"verifiable",true,"confidence",0.91,"modelVersion","fixture-1"),200);
    }
    private JsonNode invite(User owner,User target,JsonNode child,List<String> caps)throws Exception{return call("POST","/workspaces/"+owner.workspace()+"/invitations",owner,p("email",target.email(),"role","sitter","children",List.of(p("workspaceChildId",uuid(child,"workspaceChildId"),"capabilities",caps)),"validUntil",Instant.now().plusSeconds(3600)),UUID.randomUUID().toString(),200);}
    private User accept(User owner,User target,JsonNode invite)throws Exception{
        var token=call("POST","/invitations/"+invite.path("id").asText()+"/local-token",owner,Map.of(),200);
        var accepted=call("POST","/invitations/accept",target,p("token",token.path("invitationToken").asText()),"accept-1",200);return workspace(target,owner.workspace(),uuid(accepted,"membershipId"));
    }
    private void heartbeat(UUID camera,String status)throws Exception{worker("/devices/"+camera+"/heartbeat",p("observedAt",Instant.now(),"analysisObservedAt",Instant.now(),"confidence",0.9,"status",status,"safety",p("posture","stable","face","stable","edge","stable","objects","stable")),200);}
    private JsonNode analysis(User owner,UUID event)throws Exception{
        worker("/events/"+event+"/analysis",p("status","ready","modelVersion","fixture-1","causes",List.of(p("key","hunger","label","배고픔 가능성","reason","관찰이 더 필요해요")),"actions",List.of(p("key","check_state","title","아이 상태 확인","explanation","직접 확인해 주세요","instructions",List.of("얼굴과 주변 환경을 확인해 주세요")))),200);
        return call("GET","/events/"+event+"/recommendations",owner,null,200);
    }

    @Test void signupCreatesPersonalWorkspaceAndHashesTokens()throws Exception{
        User user=user();var spaces=call("GET","/me/workspaces",user,null,200);assertThat(spaces.path("items").get(0).path("type").asText()).isEqualTo("PERSONAL");
        var row=db.required("SELECT token_hash FROM user_sessions WHERE user_id=:id",p("id",user.user()),"missing");assertThat(row.get("token_hash")).isEqualTo(Secrets.hash(user.refresh())).isNotEqualTo(user.refresh());
        assertThat(call("GET","/children",user,null,200).path("items")).isEmpty();
    }
    @Test void unauthenticatedAndInternalRequestsFailWithRequestId()throws Exception{
        var failure=call("GET","/children",null,null,401);assertThat(failure.path("error").path("requestId").asText()).matches("[0-9a-f-]{36}");
        call("POST","/internal/devices",null,Map.of(),401);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    @Test void otherWorkspaceAndMembershipAloneNeverGrantChildAccess()throws Exception{
        var owner=user();var outsider=user();var child=child(owner);UUID id=uuid(child,"id");
        call("GET","/children/"+id,outsider,null,404);call("GET","/children/"+id,workspace(outsider,owner.workspace(),outsider.member()),null,404);
        UUID member=UUID.randomUUID();db.update("INSERT INTO memberships(id,workspace_id,user_id,role) VALUES(:id,:ws,:u,'staff')",p("id",member,"ws",owner.workspace(),"u",outsider.user()));
        var staff=workspace(outsider,owner.workspace(),member);call("GET","/children/"+id,staff,null,404);assertThat(call("GET","/dashboard",staff,null,200).path("items")).isEmpty();
    }
    @Test void sitterCanOnlyReadGrantedCryChildAndCannotWatchVideo()throws Exception{
        var owner=user();var sitter=user();var a=child(owner);var b=child(owner);var invited=invite(owner,sitter,a,List.of("cry:read","cry:feedback"));sitter=accept(owner,sitter,invited);
        call("GET","/children/"+a.path("id").asText()+"/events?type=cry",sitter,null,200);
        call("GET","/children/"+a.path("id").asText()+"/monitoring",sitter,null,403);
        call("GET","/children/"+b.path("id").asText()+"/events?type=cry",sitter,null,404);
        call("GET","/children/"+a.path("id").asText()+"/events",sitter,null,403);
        call("GET","/children/"+a.path("id").asText()+"/summary",sitter,null,403);
    }
    @Test void invitationsRequireVerifiedMatchingEmailAndRejectExpiration()throws Exception{
        var owner=user();var target=user();var wrong=user();var invited=invite(owner,target,child(owner),List.of("cry:read"));
        var token=call("POST","/invitations/"+invited.path("id").asText()+"/local-token",owner,Map.of(),200).path("invitationToken").asText();
        call("POST","/invitations/accept",wrong,p("token",token),"wrong-email",403);
        db.update("UPDATE users SET email_verified=false WHERE id=:id",p("id",target.user()));call("POST","/invitations/accept",target,p("token",token),"unverified",403);
        db.update("UPDATE users SET email_verified=true WHERE id=:id",p("id",target.user()));db.update("UPDATE invitations SET expires_at=now()-interval '1 second' WHERE id=:id",p("id",uuid(invited,"id")));
        assertThat(call("POST","/invitations/accept",target,p("token",token),"expired",409).path("error").path("code").asText()).isEqualTo("INVITE_EXPIRED");
    }
    @Test void invitationAcceptanceReplaysOnce()throws Exception{
        var owner=user();var target=user();var invited=invite(owner,target,child(owner),List.of("cry:read"));var token=call("POST","/invitations/"+invited.path("id").asText()+"/local-token",owner,Map.of(),200).path("invitationToken").asText();
        var first=call("POST","/invitations/accept",target,p("token",token),"accept-once",200);var second=call("POST","/invitations/accept",target,p("token",token),"accept-once",200);assertThat(second).isEqualTo(first);
        assertThat(db.list("SELECT id FROM child_access_grants WHERE invitation_id=:id",p("id",uuid(invited,"id")))).hasSize(1);
    }
    @Test void revokingInvitationInvalidatesApiSseScopeAndIssuedStreamToken()throws Exception{
        var owner=user();var target=user();var child=child(owner);UUID id=uuid(child,"id"),camera=camera(owner,id);heartbeat(camera,"live");
        var invited=invite(owner,target,child,List.of("child:read","cry:read","monitor:live","detection:read","notification:receive"));target=accept(owner,target,invited);
        var stream=call("POST","/children/"+id+"/stream-token",target,Map.of(),200);worker("/stream-tokens/introspect",p("token",stream.path("token").asText()),200);
        var jwt=decoder.decode(target.token());assertThat(notifications.children(policy.actor(jwt,target.workspace()))).contains(id);
        var connection=mvc.perform(get("/api/v1/realtime").header("Authorization","Bearer "+target.token()).header("X-Workspace-Id",target.workspace())).andReturn();
        assertThat(connection.getRequest().isAsyncStarted()).isTrue();
        call("DELETE","/invitations/"+invited.path("id").asText(),owner,null,200);
        assertThat(connection.getAsyncResult(5000)).isNull();
        call("GET","/children/"+id,target,null,404);worker("/stream-tokens/introspect",p("token",stream.path("token").asText()),403);
        assertThat(notifications.children(policy.actor(jwt,target.workspace()))).isEmpty();
    }
    @Test void expiredGrantStopsExistingAccess()throws Exception{
        var owner=user();var target=user();var child=child(owner);var invited=invite(owner,target,child,List.of("cry:read"));target=accept(owner,target,invited);
        db.update("UPDATE child_access_grants SET valid_from=now()-interval '2 hours',valid_until=now()-interval '1 hour' WHERE invitation_id=:id",p("id",uuid(invited,"id")));
        call("GET","/children/"+child.path("id").asText()+"/events?type=cry",target,null,404);
    }
    @Test void explicitShareNeedsTargetGrantAndRevokesAcrossOpenSession()throws Exception{
        var owner=user();var instituteUser=user();var institute=call("POST","/workspaces",instituteUser,p("name","아이쿠 어린이집"),200);var institution=workspace(instituteUser,uuid(institute,"id"),uuid(institute,"membershipId"));
        var child=child(owner);UUID id=uuid(child,"id");var event=detection(owner,id,camera(owner,id),"cry","caution",Instant.now().minusSeconds(10));
        var caps=List.of("child:read","cry:read","detection:read");var share=call("POST","/data-share-grants",owner,p("childId",id,"toWorkspaceId",institution.workspace(),"scopes",caps,"consentRef","signed-consent-fixture","expiresAt",Instant.now().plusSeconds(3600)),"share-1",200);
        call("GET","/children/"+id,institution,null,404);
        call("POST","/workspace-children/"+share.path("workspaceChildId").asText()+"/grants",institution,p("membershipId",institution.member(),"capabilities",caps),200);
        call("GET","/children/"+id,institution,null,200);
        assertThat(call("GET","/children/"+id+"/events?type=cry",institution,null,200).path("items")).isEmpty();
        assertThat(call("GET","/data-share-grants/"+share.path("id").asText()+"/events?type=cry",institution,null,200).path("items").size()).isEqualTo(1);
        call("GET","/events/"+event.path("id").asText(),institution,null,404);
        call("DELETE","/data-share-grants/"+share.path("id").asText(),owner,null,200);
        call("GET","/children/"+id,institution,null,404);call("GET","/data-share-grants/"+share.path("id").asText()+"/events?type=cry",institution,null,404);
        call("GET","/children/"+id,owner,null,200);
    }
    @Test void offlineObstructedAndStaleAnalysisNeverBecomeStable()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id");var state=call("GET","/children/"+child+"/monitoring",owner,null,200);
        assertThat(state.path("streamStatus").asText()).isEqualTo("offline");assertThat(state.path("safety").path("face").asText()).isEqualTo("unknown");assertThat(state.path("sensors").get(0).path("value").isNull()).isTrue();
        UUID camera=camera(owner,child);heartbeat(camera,"unverifiable");assertThat(call("GET","/children/"+child+"/monitoring",owner,null,200).path("streamStatus").asText()).isEqualTo("unverifiable");
        worker("/devices/"+camera+"/heartbeat",p("observedAt",Instant.now(),"status","live","confidence",0.9,"analysisObservedAt",Instant.now().minusSeconds(120)),200);
        assertThat(call("GET","/dashboard",owner,null,200).path("items").get(0).path("severity").asText()).isEqualTo("unknown");
        var low=worker("/detections",p("workspaceId",owner.workspace(),"childId",child,"sourceId",camera,"sourceEventId","low-confidence","type","face_covered","severity","immediate","startedAt",Instant.now(),"confidence",0.1,"verifiable",true),200);
        assertThat(low.path("status").asText()).isEqualTo("unverifiable");assertThat(low.path("severity").asText()).isEqualTo("unknown");
    }
    @Test void sensorValuesRequireActualRecentMeasurements()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),camera=camera(owner,child);worker("/devices/"+camera+"/readings",p("type","temperature","value",23.5,"observedAt",Instant.now()),200);
        var sensors=call("GET","/children/"+child+"/monitoring",owner,null,200).path("sensors");assertThat(sensors.get(0).path("connected").asBoolean()).isTrue();assertThat(sensors.get(0).path("value").asDouble()).isEqualTo(23.5);assertThat(sensors.get(1).path("value").isNull()).isTrue();
    }
    @Test void readingAndAcknowledgingNeverResolveAndNeedsHelpRemainsChecking()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),event=uuid(detection(owner,child,camera(owner,child),"face_covered","immediate",Instant.now().minusSeconds(5)),"id");
        assertThat(outbox.dispatchOne()).isTrue();var inbox=call("GET","/notifications",owner,null,200);UUID note=uuid(inbox.path("items").get(0),"id");
        call("PATCH","/notifications/"+note+"/read",owner,Map.of(),200);assertThat(call("GET","/events/"+event,owner,null,200).path("status").asText()).isEqualTo("new");
        call("POST","/events/"+event+"/acknowledgements",owner,Map.of(),200);assertThat(call("GET","/events/"+event,owner,null,200).path("status").asText()).isEqualTo("acknowledged");
        var help=call("POST","/events/"+event+"/resolution",owner,p("result","needs_help"),"help",200);assertThat(help.path("status").asText()).isEqualTo("checking");assertThat(help.path("resolvedAt").isNull()).isTrue();
        var resolved=call("POST","/events/"+event+"/resolution",owner,p("result","checked_safe","note","현장 확인"),"resolve",200);assertThat(resolved.path("status").asText()).isEqualTo("resolved");
        assertThat(call("POST","/events/"+event+"/resolution",owner,p("result","checked_safe","note","현장 확인"),"resolve",200)).isEqualTo(resolved);
        call("POST","/events/"+event+"/resolution",owner,p("result","false_alarm"),"resolve-new",409);
    }
    @Test void recommendationsPendingAndFailedReturnSafeFallbackWithoutFabricatedScores()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),event=uuid(detection(owner,child,camera(owner,child),"cry","caution",Instant.now().minusSeconds(3)),"id");
        var pending=call("GET","/events/"+event+"/recommendations",owner,null,200);assertThat(pending.path("status").asText()).isEqualTo("pending");assertThat(pending.path("fallbackGuide").size()).isGreaterThan(0);assertThat(pending.path("causes")).isEmpty();
        worker("/events/"+event+"/analysis",p("status","failed","modelVersion","fixture","causes",List.of(),"actions",List.of()),200);
        var failed=call("GET","/events/"+event+"/recommendations",owner,null,200);assertThat(failed.path("status").asText()).isEqualTo("failed");assertThat(failed.path("fallbackGuide").size()).isGreaterThan(0);
    }
    @Test void feedbackIsIdempotentAndNotAttemptedIsIndependentFromResponse()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),event=uuid(detection(owner,child,camera(owner,child),"cry","caution",Instant.now().minusSeconds(3)),"id");var recommendations=analysis(owner,event);UUID action=uuid(recommendations.path("actions").get(0),"id");
        var body=p("attempts",List.of(p("actionId",action,"state","not_attempted")),"response","continues");var saved=call("POST","/events/"+event+"/feedback",owner,body,"feedback-1",200);
        assertThat(call("POST","/events/"+event+"/feedback",owner,body,"feedback-1",200)).isEqualTo(saved);
        assertThat(db.list("SELECT * FROM care_feedback",Map.of())).hasSize(1);assertThat(db.required("SELECT action_state FROM action_attempts",Map.of(),"missing").get("action_state")).isEqualTo("not_attempted");
        call("POST","/events/"+event+"/feedback",owner,p("attempts",List.of(),"response","stopped"),"feedback-1",409);
        call("POST","/events/"+event+"/feedback",owner,body,"other-key",409);
        assertThat(recommendations.path("causes").get(0).has("score")).isFalse();assertThat(db.list("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE '%feeding%'",Map.of())).isEmpty();
    }
    @Test void concurrentFeedbackWritesCommitOnlyOnce()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),event=uuid(detection(owner,child,camera(owner,child),"cry","caution",Instant.now().minusSeconds(3)),"id");var body=p("attempts",List.of(),"response","unknown");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->call("POST","/events/"+event+"/feedback",owner,body,"race",200));var b=pool.submit(()->call("POST","/events/"+event+"/feedback",owner,body,"race",200));assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }assertThat(db.list("SELECT id FROM care_feedback",Map.of())).hasSize(1);
    }
    @Test void feedbackCannotReferenceAnotherEventOrNonCry()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),camera=camera(owner,child);UUID a=uuid(detection(owner,child,camera,"cry","caution",Instant.now().minusSeconds(20)),"id"),b=uuid(detection(owner,child,camera,"cry","caution",Instant.now().minusSeconds(3)),"id");UUID action=uuid(analysis(owner,a).path("actions").get(0),"id");
        call("POST","/events/"+b+"/feedback",owner,p("attempts",List.of(p("actionId",action,"state","attempted")),"response","reduced"),"foreign-action",404);
        UUID risk=uuid(detection(owner,child,camera,"face_covered","immediate",Instant.now().minusSeconds(2)),"id");call("POST","/events/"+risk+"/feedback",owner,p("attempts",List.of(),"response","unknown"),"non-cry",422);
        assertThat(db.list("SELECT id FROM care_feedback",Map.of())).isEmpty();
    }
    @Test void outboxSurvivesOfflineClientAndRechecksRevokedRecipients()throws Exception{
        var owner=user();var sitter=user();var child=child(owner);UUID id=uuid(child,"id");var invited=invite(owner,sitter,child,List.of("detection:read","notification:receive"));sitter=accept(owner,sitter,invited);
        detection(owner,id,camera(owner,id),"face_covered","immediate",Instant.now().minusSeconds(2));call("DELETE","/invitations/"+invited.path("id").asText(),owner,null,200);
        assertThat(outbox.dispatchOne()).isTrue();assertThat(outbox.dispatchOne()).isFalse();
        assertThat(call("GET","/notifications",sitter,null,200).path("items")).isEmpty();assertThat(call("GET","/notifications",owner,null,200).path("items").size()).isEqualTo(1);
        var actor=policy.actor(decoder.decode(owner.token()),owner.workspace());var replay=notifications.replay(actor,0,notifications.children(actor));assertThat(replay).hasSize(1);assertThat(replay.getFirst()).doesNotContainKeys("summary","displayName");
        assertThat(call("GET","/children/"+id+"/events",owner,null,200).path("items").size()).isEqualTo(1);
    }
    @Test void keysetCursorsHaveNoDuplicatesAndAreBoundToChild()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id"),other=uuid(child(owner),"id"),camera=camera(owner,child);Instant at=Instant.now().minusSeconds(10);
        for(int i=0;i<3;i++)detection(owner,child,camera,"cry","caution",at);
        var first=call("GET","/children/"+child+"/events?type=cry&limit=2",owner,null,200);String cursor=first.path("nextCursor").asText();var second=call("GET","/children/"+child+"/events?type=cry&limit=2&cursor="+cursor,owner,null,200);
        assertThat(first.path("items").size()).isEqualTo(2);assertThat(second.path("items").size()).isEqualTo(1);assertThat(List.of(first.path("items").get(0).path("id").asText(),first.path("items").get(1).path("id").asText())).doesNotContain(second.path("items").get(0).path("id").asText());
        call("GET","/children/"+other+"/events?type=cry&cursor="+cursor,owner,null,422);call("GET","/children/"+child+"/events?limit=101",owner,null,422);
    }
    @Test void dashboardSortsByRiskAndMissingSummaryDataStaysNull()throws Exception{
        var owner=user();UUID unknown=uuid(child(owner),"id"),caution=uuid(child(owner),"id"),immediate=uuid(child(owner),"id"),stable=uuid(child(owner),"id");
        detection(owner,caution,camera(owner,caution),"cry","caution",Instant.now().minusSeconds(3));detection(owner,immediate,camera(owner,immediate),"face_covered","immediate",Instant.now().minusSeconds(3));heartbeat(camera(owner,stable),"live");
        var items=call("GET","/dashboard",owner,null,200).path("items");assertThat(items.get(0).path("id").asText()).isEqualTo(immediate.toString());assertThat(items.get(1).path("id").asText()).isEqualTo(caution.toString());assertThat(items.get(2).path("id").asText()).isEqualTo(unknown.toString());assertThat(items.get(3).path("severity").asText()).isEqualTo("stable");
        var summary=call("GET","/children/"+unknown+"/summary",owner,null,200);assertThat(summary.path("hasData").asBoolean()).isFalse();assertThat(summary.path("cryCount").isNull()).isTrue();
    }
    @Test void routinesPersistCompletionAndRejectCrossChildWrite()throws Exception{
        var owner=user();UUID a=uuid(child(owner),"id"),b=uuid(child(owner),"id");var routine=call("POST","/children/"+a+"/routines",owner,p("type","play","title","놀기","scheduledAt",Instant.now()),201);String path="/children/"+a+"/routines/"+routine.path("id").asText();
        var done=call("PATCH",path,owner,p("status","done"),200);assertThat(done.path("completedAt").isNull()).isFalse();
        call("PATCH","/children/"+b+"/routines/"+routine.path("id").asText(),owner,p("status","done"),404);
        assertThat(call("PATCH",path,owner,p("status","todo"),200).path("completedAt").isNull()).isTrue();call("DELETE",path,owner,null,200);assertThat(call("GET","/children/"+a+"/routines",owner,null,200).path("items")).isEmpty();
    }
    @Test void refreshRotationAndReuseRevokeEntireFamily()throws Exception{
        var owner=user();var rotated=call("POST","/auth/refresh",null,p("refreshToken",owner.refresh()),200);call("GET","/me/workspaces",owner,null,401);
        var current=new User(rotated.path("accessToken").asText(),rotated.path("refreshToken").asText(),owner.workspace(),owner.member(),owner.user(),owner.email());call("GET","/me/workspaces",current,null,200);
        assertThat(call("POST","/auth/refresh",null,p("refreshToken",owner.refresh()),401).path("error").path("code").asText()).isEqualTo("REFRESH_REUSE_DETECTED");call("GET","/me/workspaces",current,null,401);
    }
    @Test void sourceBindingAndCompositeForeignKeysEnforceTenantIntegrity()throws Exception{
        var a=user();var b=user();UUID ac=uuid(child(a),"id"),bc=uuid(child(b),"id"),camera=camera(a,ac);
        worker("/detections",p("workspaceId",b.workspace(),"childId",bc,"sourceId",camera,"sourceEventId","forged","type","cry","severity","caution","startedAt",Instant.now(),"verifiable",true,"confidence",0.9),404);
        UUID link=(UUID)db.required("SELECT id FROM workspace_children WHERE child_id=:id",p("id",ac),"missing").get("id");
        assertThatThrownBy(()->db.update("INSERT INTO child_access_grants(id,workspace_child_id,membership_id,workspace_id,capabilities,valid_from) VALUES(:id,:wc,:m,:ws,'[\"cry:read\"]',now())",p("id",UUID.randomUUID(),"wc",link,"m",b.member(),"ws",a.workspace()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void cannotDisableImportantAlertsAndLogoutImmediatelyInvalidatesJwt()throws Exception{
        var owner=user();call("PATCH","/preferences/notifications",owner,p("importantAlerts",false),422);
        var preferences=call("PATCH","/preferences/notifications",owner,p("crySound",false,"pushEnabled",true),200);assertThat(preferences.path("importantAlerts").asBoolean()).isTrue();assertThat(preferences.path("pushDeliveryAvailable").asBoolean()).isFalse();
        call("POST","/auth/logout",owner,Map.of(),200);call("GET","/children",owner,null,401);
    }
    @Test void failedOutboxRetriesThenDeadLettersAndCanBeRequeued()throws Exception{
        var owner=user();UUID child=uuid(child(owner),"id");detection(owner,child,camera(owner,child),"cry","caution",Instant.now().minusSeconds(3));
        var task=db.required("SELECT * FROM outbox_messages",Map.of(),"missing");UUID id=id(task,"id");Object payload=task.get("payload");
        db.update("UPDATE outbox_messages SET payload=cast(:payload as jsonb) WHERE id=:id",p("id",id,"payload",db.json(p("childId",child,"severity","caution","recipients",List.of("invalid-recipient")))));
        for(int i=0;i<5;i++){assertThat(outbox.dispatchOne()).isFalse();db.update("UPDATE outbox_messages SET next_attempt_at=now() WHERE id=:id",p("id",id));}
        var failed=db.required("SELECT * FROM outbox_messages WHERE id=:id",p("id",id),"missing");assertThat(failed.get("dead_lettered_at")).isNotNull();assertThat(((Number)failed.get("retry_count")).intValue()).isEqualTo(5);assertThat(db.list("SELECT id FROM notifications",Map.of())).isEmpty();
        db.update("UPDATE outbox_messages SET payload=cast(:payload as jsonb) WHERE id=:id",p("id",id,"payload",db.json(payload)));outbox.retry(id);assertThat(outbox.dispatchOne()).isTrue();assertThat(db.list("SELECT id FROM notifications",Map.of())).hasSize(1);
    }
    @Test void acknowledgementsArePerUserEvenWhenGlobalStatusChanges()throws Exception{
        var owner=user();var target=user();var child=child(owner);UUID id=uuid(child,"id");var invited=invite(owner,target,child,List.of("detection:read","detection:ack"));target=accept(owner,target,invited);
        UUID event=uuid(detection(owner,id,camera(owner,id),"face_covered","immediate",Instant.now().minusSeconds(2)),"id");call("POST","/events/"+event+"/acknowledgements",owner,Map.of(),200);
        var detail=call("GET","/events/"+event,target,null,200);assertThat(detail.path("status").asText()).isEqualTo("acknowledged");assertThat(detail.path("acknowledgedAt").isNull()).isTrue();
        call("POST","/events/"+event+"/acknowledgements",target,Map.of(),200);assertThat(db.list("SELECT id FROM event_acknowledgements WHERE event_id=:id",p("id",event))).hasSize(2);
    }
}
