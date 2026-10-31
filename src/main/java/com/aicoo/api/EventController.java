package com.aicoo.api;

import com.aicoo.detection.EventService;
import com.aicoo.monitoring.MonitoringService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class EventController {
    private final Policy policy;private final EventService events;private final MonitoringService monitoring;
    public EventController(Policy policy,EventService events,MonitoringService monitoring){this.policy=policy;this.events=events;this.monitoring=monitoring;}
    @GetMapping("/children/{child}/events") Object list(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child,@RequestParam(required=false)String type,@RequestParam(defaultValue="30")int limit,@RequestParam(required=false)String cursor){return events.list(policy.actor(jwt,ws),child,type,limit,cursor);}
    @GetMapping("/events/{event}") Object detail(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID event){return events.detail(policy.actor(jwt,ws),event);}
    @PostMapping("/events/{event}/acknowledgements") Object acknowledge(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID event){return events.acknowledge(policy.actor(jwt,ws),event);}
    @PostMapping("/events/{event}/resolution") Object resolve(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID event,@RequestHeader("Idempotency-Key")String key,@RequestBody @Valid EventService.Resolution body){return events.resolve(policy.actor(jwt,ws),event,body,key);}
    @GetMapping("/children/{child}/monitoring") Object monitoring(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child){return monitoring.state(policy.actor(jwt,ws),child);}
    @PostMapping("/children/{child}/stream-token") Object token(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child){return monitoring.streamToken(policy.actor(jwt,ws),child);}
}
