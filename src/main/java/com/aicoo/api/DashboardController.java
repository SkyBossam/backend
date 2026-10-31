package com.aicoo.api;

import com.aicoo.care.*;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class DashboardController {
    private final Policy policy;private final DashboardService dashboard;
    public DashboardController(Policy policy,DashboardService dashboard){this.policy=policy;this.dashboard=dashboard;}
    @GetMapping("/dashboard") Object dashboard(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestParam(required=false)UUID childId){return dashboard.dashboard(policy.actor(jwt,ws),childId);}
}
