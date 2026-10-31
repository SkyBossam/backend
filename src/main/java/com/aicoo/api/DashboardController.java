package com.aicoo.api;

import com.aicoo.care.*;
import com.aicoo.workspace.SharedDataService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class DashboardController {
    private final Policy policy;private final DashboardService dashboard;private final SharedDataService shares;private final PrivacyService privacy;
    public DashboardController(Policy policy,DashboardService dashboard,SharedDataService shares,PrivacyService privacy){this.policy=policy;this.dashboard=dashboard;this.shares=shares;this.privacy=privacy;}
    @GetMapping("/dashboard") Object dashboard(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestParam(required=false)UUID childId){return dashboard.dashboard(policy.actor(jwt,ws),childId);}
    @GetMapping("/data-share-grants/{id}/events") Object shared(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID id,@RequestParam(required=false)String type,@RequestParam(defaultValue="30")int limit,@RequestParam(required=false)String cursor){return shares.list(policy.actor(jwt,ws),id,type,limit,cursor);}
    @GetMapping("/privacy/export") Object export(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws){return privacy.export(policy.actor(jwt,ws));}
    @GetMapping("/privacy/requests") Object requests(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws){return privacy.requests(policy.actor(jwt,ws));}
    @PostMapping("/privacy/requests") Object request(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestBody @Valid PrivacyService.Request body){return privacy.request(policy.actor(jwt,ws),body);}
}
