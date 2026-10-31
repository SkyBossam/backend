package com.aicoo.api;
import com.aicoo.care.CareService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/v1") public class CareController {
    private final CareService care;private final Policy policy;public CareController(CareService care,Policy policy){this.care=care;this.policy=policy;}
    @GetMapping("/children") Object children(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws){return care.children(policy.actor(jwt,ws));}
    @PostMapping("/children") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) Object create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody CareService.ChildWrite body){return care.createChild(policy.actor(jwt,ws),body,key);}
    @GetMapping("/children/{childId}") Object child(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId){return care.child(policy.actor(jwt,ws),childId);}
    @PatchMapping("/children/{childId}") Object update(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@Valid @RequestBody CareService.ChildWrite body){return care.updateChild(policy.actor(jwt,ws),childId,body);}
    @GetMapping("/children/{childId}/summary") Object summary(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@RequestParam(required=false)LocalDate date){return care.summary(policy.actor(jwt,ws),childId,date==null?LocalDate.now(java.time.ZoneId.of("Asia/Seoul")):date);}
    @GetMapping("/children/{childId}/routines") Object routines(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@RequestParam(required=false)LocalDate date){return care.routines(policy.actor(jwt,ws),childId,date);}
    @PostMapping("/children/{childId}/routines") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) Object routine(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody CareService.RoutineWrite body){return care.createRoutine(policy.actor(jwt,ws),childId,body,key);}
    @PatchMapping("/children/{childId}/routines/{routineId}") Object updateRoutine(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@PathVariable UUID routineId,@Valid @RequestBody CareService.RoutineWrite body){return care.updateRoutine(policy.actor(jwt,ws),childId,routineId,body);}
    @DeleteMapping("/children/{childId}/routines/{routineId}") Object deleteRoutine(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID ws,@PathVariable UUID childId,@PathVariable UUID routineId){care.deleteRoutine(policy.actor(jwt,ws),childId,routineId);return Map.of("deleted",true);}
}
