package com.aicoo.api;

import com.aicoo.notification.*;
import com.aicoo.security.Policy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class NotificationController {
    private final Policy policy;private final NotificationService notifications;private final RealtimeService realtime;private final OutboxWorker worker;
    public NotificationController(Policy policy,NotificationService notifications,RealtimeService realtime,OutboxWorker worker){this.policy=policy;this.notifications=notifications;this.realtime=realtime;this.worker=worker;}
    @GetMapping("/preferences/notifications") Object preferences(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws){return notifications.preferences(policy.actor(jwt,ws));}
    @PatchMapping("/preferences/notifications") Object preferences(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestBody NotificationService.Preferences body){return notifications.preferences(policy.actor(jwt,ws),body);}
    @GetMapping("/notifications") Object list(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestParam(defaultValue="false")boolean unread,@RequestParam(defaultValue="30")int limit,@RequestParam(required=false)String cursor){return notifications.list(policy.actor(jwt,ws),unread,limit,cursor);}
    @PatchMapping("/notifications/{id}/read") Object read(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID id){return notifications.read(policy.actor(jwt,ws),id);}
    @GetMapping(value="/realtime",produces="text/event-stream") SseEmitter realtime(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestHeader(value="Last-Event-ID",required=false)String lastId){return realtime.subscribe(jwt,ws,lastId);}
    @GetMapping("/internal/outbox") Object outbox(){return worker.health();}
    @PostMapping("/internal/outbox/{id}/retry") Object retry(@PathVariable UUID id){return worker.retry(id);}
}
