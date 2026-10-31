package com.aicoo.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI apiDocumentation(){return new OpenAPI().info(new Info().title("아이쿠 aicoo API").version("1.0.0").description("아이별 권한과 워크스페이스를 확인하는 돌봄 API. 인증 후 X-Workspace-Id를 지정하세요. 내부 API는 별도 X-Worker-Key가 필요합니다.")).components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))).addSecurityItem(new SecurityRequirement().addList("bearerAuth"));}
}
