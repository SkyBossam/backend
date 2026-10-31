package com.aicoo.api;

import com.aicoo.monitoring.MonitoringService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class EventController {
    private final Policy policy;private final MonitoringService monitoring;
    public EventController(Policy policy,MonitoringService monitoring){this.policy=policy;this.monitoring=monitoring;}
    @GetMapping("/children/{child}/monitoring") Object monitoring(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child){return monitoring.state(policy.actor(jwt,ws),child);}
    @PostMapping("/children/{child}/stream-token") Object token(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child){return monitoring.streamToken(policy.actor(jwt,ws),child);}
}
