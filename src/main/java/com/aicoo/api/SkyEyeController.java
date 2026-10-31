package com.aicoo.api;

import com.aicoo.device.SkyEyeService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class SkyEyeController {
    private final SkyEyeService sky;private final Policy policy;
    public SkyEyeController(SkyEyeService sky,Policy policy){this.sky=sky;this.policy=policy;}
    @PostMapping("/device/sky-eye/status") Object status(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestBody @Valid SkyEyeService.Status body){return sky.status(token,body);}
    @PostMapping("/device/sky-eye/events") Object event(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestBody @Valid SkyEyeService.Event body){return sky.event(token,body);}
    @GetMapping("/device/sky-eye/commands") Object commands(@RequestHeader(value="X-Device-Token",defaultValue="")String token){return sky.commands(token);}
    @PostMapping("/device/sky-eye/commands/{command}/receipt") Object receipt(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@PathVariable UUID command,@RequestBody @Valid SkyEyeService.Receipt body){return sky.receipt(token,command,body);}
    @GetMapping("/children/{child}/sky-eye") Object snapshot(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child){return sky.snapshot(policy.actor(jwt,ws),child);}
    @PostMapping("/children/{child}/sky-eye/feedback") Object feedback(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID child,@RequestBody @Valid SkyEyeService.Feedback body){return sky.feedback(policy.actor(jwt,ws),child,body);}
}
