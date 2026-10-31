package com.aicoo.security;

import com.aicoo.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.io.IOException;
import static com.aicoo.common.Db.p;

@Configuration public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean JwtEncoder encoder(Secrets secrets){return new NimbusJwtEncoder(new ImmutableSecret<>(secrets.jwtKey));}
    @Bean JwtDecoder decoder(Secrets secrets,Db db,@Value("${app.issuer}")String issuer){
        var decoder=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secrets.jwtKey,"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> session=jwt->{try{UUID sid=UUID.fromString(jwt.getClaimAsString("sid"));UUID uid=UUID.fromString(jwt.getSubject());
            boolean active=jwt.getAudience().contains("aicoo-api")&&db.one("SELECT s.id FROM user_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=:sid AND s.user_id=:uid AND s.revoked_at IS NULL AND s.expires_at>now() AND u.status='active'",p("sid",sid,"uid",uid)).isPresent();
            return active?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }catch(Exception e){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));}};
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),session));return decoder;
    }
    @Bean CorsConfigurationSource cors(@Value("${app.cors-origins}")String origins){var config=new CorsConfiguration();config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());config.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));config.setAllowedHeaders(List.of("Authorization","Content-Type","X-Workspace-Id","Idempotency-Key","Last-Event-ID"));config.setExposedHeaders(List.of("X-Request-Id"));config.setAllowCredentials(false);var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",config);return source;}
    @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper mapper,Secrets secrets) throws Exception {
        http.csrf(c->c.disable()).cors(c->{}).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health/**","/api/v1/auth/signup","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/verify-email","/api/v1/auth/request-verification","/docs","/swagger-ui/**","/v3/api-docs/**","/api/v1/internal/**").permitAll().anyRequest().authenticated());
        http.oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((req,res,e)->{res.setStatus(401);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Errors.body("AUTHENTICATION_REQUIRED","유효한 로그인이 필요해요.",req.getAttribute("requestId")));}));
        http.exceptionHandling(e->e.accessDeniedHandler((req,res,error)->{res.setStatus(403);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Errors.body("ACCESS_DENIED","접근 권한이 없어요.",req.getAttribute("requestId")));}));
        return http.build();
    }
    @Bean FilterRegistrationBean<OncePerRequestFilter> requestMetadata(Secrets secrets,ObjectMapper mapper){var filter=new OncePerRequestFilter(){
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            String requestId=UUID.randomUUID().toString();req.setAttribute("requestId",requestId);res.setHeader("X-Request-Id",requestId);res.setHeader("Cache-Control","no-store");
            if(secrets.production&&!req.isSecure()&&!req.getRequestURI().startsWith("/actuator/health")){res.setStatus(426);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Errors.body("HTTPS_REQUIRED","보안 연결이 필요해요.",requestId));return;}
            if(req.getRequestURI().startsWith("/api/v1/internal/")&&!Secrets.equal(secrets.workerKey,req.getHeader("X-Worker-Key"))){res.setStatus(401);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Errors.body("WORKER_AUTH_REQUIRED","내부 서비스 인증이 필요해요.",requestId));return;}
            chain.doFilter(req,res);
        }
    };var registration=new FilterRegistrationBean<OncePerRequestFilter>(filter);registration.setOrder(Ordered.HIGHEST_PRECEDENCE);return registration;}
}
