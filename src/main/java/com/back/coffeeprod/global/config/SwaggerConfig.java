package com.back.coffeeprod.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        // 1. JWT 인증 체계 설정
        String jwtSchemeName = "jwtAuth";
        SecurityRequirement serucRequirement = new SecurityRequirement().addList(jwtSchemeName);

        Components components = new Components()
                .addSecuritySchemes(jwtSchemeName, new SecurityScheme()
                        .name(jwtSchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));

        // 2. 문서 정보 및 보안 설정
        return new OpenAPI()
                .info(new Info()
                        .title("커피 커머스 API 명세서")
                        .description("원두 큐레이션 커머스 프로젝트의 백엔드 API 문서")
                        .version("v1.0.0"))
                .addSecurityItem(serucRequirement)
                .components(components);
    }
}
