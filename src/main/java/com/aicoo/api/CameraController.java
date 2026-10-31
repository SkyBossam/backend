package com.aicoo.api;

import com.aicoo.common.ApiException;
import com.aicoo.monitoring.CameraRelay;
import com.aicoo.security.Policy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class CameraController {
    private final CameraRelay relay;private final Policy policy;
    public CameraController(CameraRelay relay,Policy policy){this.relay=relay;this.policy=policy;}
    @PostMapping(value="/device/camera/frame",consumes="image/jpeg")
    Object upload(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestHeader("X-Frame-Captured-At")String at,HttpServletRequest request)throws IOException {
        if(request.getContentLengthLong()>CameraRelay.MAX_BYTES)throw new ApiException(413,"FRAME_TOO_LARGE","영상 프레임이 너무 커요.");
        Instant captured;try{captured=Instant.parse(at);}catch(DateTimeParseException e){throw ApiException.invalid("INVALID_FRAME_TIME");}
        return relay.upload(token,captured,request.getInputStream().readNBytes(CameraRelay.MAX_BYTES+1));
    }
    @GetMapping("/children/{child}/devices/{device}/camera/frame")
    ResponseEntity<byte[]> frame(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Id")UUID workspace,@PathVariable UUID child,@PathVariable UUID device) {
        var frame=relay.read(policy.actor(jwt,workspace),child,device);
        if(frame==null)return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(frame.bytes());
    }
}
