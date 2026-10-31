package com.aicoo.api;

import com.aicoo.care.CareHistoryService;
import com.aicoo.security.Policy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/children/{childId}/care-history")
public class CareHistoryController {
    private final CareHistoryService history;
    private final Policy policy;
    public CareHistoryController(CareHistoryService history,Policy policy){this.history=history;this.policy=policy;}
    @GetMapping Object list(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id") UUID workspace,
        @PathVariable UUID childId,@RequestParam String kind,@RequestParam(defaultValue="100") int limit,
        @RequestParam(required=false) String cursor) {
        return history.list(policy.actor(jwt,workspace),childId,kind,limit,cursor);
    }
    @PutMapping Object save(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id") UUID workspace,
        @PathVariable UUID childId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CareHistoryService.Changes body) {
        return history.save(policy.actor(jwt,workspace),childId,body,key);
    }
}
