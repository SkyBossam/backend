package com.aicoo.api;

import com.aicoo.device.*;
import com.aicoo.security.Policy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class PairingController {
    private final PairingService pairing;private final PairingRateLimit limits;private final Policy policy;
    public PairingController(PairingService pairing,PairingRateLimit limits,Policy policy){this.pairing=pairing;this.limits=limits;this.policy=policy;}
    @PostMapping("/pairing/challenges") Object challenge(@RequestBody @Valid PairingService.Challenge body,HttpServletRequest request){limits.check("challenge:"+request.getRemoteAddr(),40);return pairing.challenge(body);}
    @PostMapping("/pairing/start") Object start(@RequestBody @Valid PairingService.Proof body,HttpServletRequest request){limits.check("start:"+request.getRemoteAddr(),20);return pairing.start(body);}
    @PostMapping("/pairing/token") Object token(@RequestBody @Valid PairingService.Poll body,HttpServletRequest request){limits.check("poll:"+request.getRemoteAddr(),600);return pairing.poll(body);}
    @PostMapping("/device/authenticate") Object authenticate(@RequestBody @Valid PairingService.Proof body,HttpServletRequest request){limits.check("authenticate:"+request.getRemoteAddr(),40);return pairing.authenticate(body);}
    @PostMapping("/device-pairings/inspect") Object inspect(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestBody @Valid PairingService.Code body,HttpServletRequest request){var actor=policy.actor(jwt,ws);limits.check("code-ip:"+request.getRemoteAddr(),30);limits.check("code-user:"+actor.userId(),10);return pairing.inspect(actor,body);}
    @PostMapping("/device-pairings/approve") Object approve(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestHeader("Idempotency-Key")String key,@RequestBody @Valid PairingService.Approval body,HttpServletRequest request){var actor=policy.actor(jwt,ws);limits.check("approval-ip:"+request.getRemoteAddr(),30);limits.check("approval-user:"+actor.userId(),10);return pairing.approve(actor,body,key);}
    @GetMapping("/children/{id}/devices") Object devices(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID id){return pairing.list(policy.actor(jwt,ws),id);}
    @DeleteMapping("/devices/{id}") Object unlink(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID id){return pairing.unlink(policy.actor(jwt,ws),id);}
}
