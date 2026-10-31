package com.aicoo.api;
import com.aicoo.security.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;

@RestController @RequestMapping("/api/v1/auth") public class AuthController {
    record Token(@NotBlank @Size(max=256)String token){} record Refresh(@NotBlank @Size(max=256)String refreshToken){} record Email(@NotBlank @jakarta.validation.constraints.Email String email){}
    private final IdentityService identity; public AuthController(IdentityService identity){this.identity=identity;}
    @PostMapping("/signup") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) Object signup(@Valid @RequestBody IdentityService.Signup body,HttpServletRequest req){identity.limit("signup:"+req.getRemoteAddr());return identity.signup(body);}
    @PostMapping("/login") Object login(@Valid @RequestBody IdentityService.Login body,HttpServletRequest req){identity.limit("login-ip:"+req.getRemoteAddr());identity.limit("login-identifier:"+body.identifier().trim().toLowerCase(Locale.ROOT));return identity.login(body);}
    @PostMapping("/refresh") Object refresh(@Valid @RequestBody Refresh body,HttpServletRequest req){identity.limit("refresh:"+req.getRemoteAddr());return identity.refresh(body.refreshToken());}
    @PostMapping("/logout") Object logout(@AuthenticationPrincipal Jwt jwt){identity.logout(jwt);return Map.of("loggedOut",true);}
    @PostMapping("/verify-email") Object verify(@Valid @RequestBody Token body,HttpServletRequest req){identity.limit("verify:"+req.getRemoteAddr());identity.verify(body.token());return Map.of("emailVerified",true);}
    @PostMapping("/request-verification") Object request(@Valid @RequestBody Email body,HttpServletRequest req){identity.limit("verification:"+req.getRemoteAddr());return identity.requestVerification(body.email());}
}
