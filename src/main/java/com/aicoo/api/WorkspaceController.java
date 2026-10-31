package com.aicoo.api;
import com.aicoo.workspace.WorkspaceService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1") public class WorkspaceController {
    record Accept(@NotBlank @Size(max=256)String token){}
    private final WorkspaceService service;private final Policy policy;public WorkspaceController(WorkspaceService service,Policy policy){this.service=service;this.policy=policy;}
    @GetMapping("/me/workspaces") Object mine(@AuthenticationPrincipal Jwt jwt){return service.workspaces(UUID.fromString(jwt.getSubject()));}
    @PostMapping("/workspaces") Object create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody WorkspaceService.NewWorkspace body){return service.create(jwt,body);}
    @GetMapping("/workspaces/{workspaceId}/members") Object members(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID workspaceId,@RequestHeader("X-Workspace-Id")UUID ws){if(!ws.equals(workspaceId))throw com.aicoo.common.ApiException.notFound("WORKSPACE_NOT_ACCESSIBLE");return service.members(policy.actor(jwt,ws));}
    @GetMapping("/workspace-children") Object links(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws){return service.links(policy.actor(jwt,ws));}
    @PostMapping("/workspaces/{workspaceId}/invitations") Object invite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID workspaceId,@RequestHeader("X-Workspace-Id")UUID ws,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody WorkspaceService.Invite body){if(!ws.equals(workspaceId))throw com.aicoo.common.ApiException.notFound("WORKSPACE_NOT_ACCESSIBLE");return service.invite(policy.actor(jwt,ws),body,key);}
    @PostMapping("/invitations/{id}/local-token") Object localToken(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("X-Workspace-Id")UUID ws){return service.invitationToken(policy.actor(jwt,ws),id);}
    @PostMapping("/invitations/accept") Object accept(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Accept body){return service.accept(jwt,body.token(),key);}
    @DeleteMapping("/invitations/{id}") Object revokeInvite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("X-Workspace-Id")UUID ws){service.revokeInvitation(policy.actor(jwt,ws),id);return Map.of("revoked",true);}
    @PostMapping("/workspace-children/{id}/grants") Object grant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("X-Workspace-Id")UUID ws,@Valid @RequestBody WorkspaceService.Grant body){return service.grant(policy.actor(jwt,ws),id,null,body);}
    @PatchMapping("/workspace-children/{id}/grants/{grantId}") Object updateGrant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID grantId,@RequestHeader("X-Workspace-Id")UUID ws,@Valid @RequestBody WorkspaceService.Grant body){return service.grant(policy.actor(jwt,ws),id,grantId,body);}
    @DeleteMapping("/workspace-children/{id}/grants/{grantId}") Object revokeGrant(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID grantId,@RequestHeader("X-Workspace-Id")UUID ws){service.revokeGrant(policy.actor(jwt,ws),id,grantId);return Map.of("revoked",true);}
    @PostMapping("/data-share-grants") Object share(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody WorkspaceService.Share body){return service.share(policy.actor(jwt,ws),body,key);}
    @DeleteMapping("/data-share-grants/{id}") Object revokeShare(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("X-Workspace-Id")UUID ws){service.revokeShare(policy.actor(jwt,ws),id);return Map.of("revoked",true);}
}
