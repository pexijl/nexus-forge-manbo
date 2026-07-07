package com.nexusforge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 全局配置
 *
 * 作用：
 * 1. 配置文档基本信息（标题、版本、联系方式）
 * 2. 配置全局 JWT Bearer 认证 —— 所有标了 @SecurityScheme 的接口都可在 Swagger UI 顶部 "Authorize" 填 token
 */
@Configuration
public class OpenApiConfig {

    /**
     * 全局 OpenAPI bean
     *
     * - info: 文档头部信息
     * - addSecurityItem: 声明"所有接口默认需要 Bearer 认证"
     *   （需要公开的接口用 @SecurityRequirements 覆盖）
     * - components.securitySchemes: 定义 "bearer-jwt" 认证方案
     */
    @Bean
    public OpenAPI nexusForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nexus Forge API")
                        .description("""
                                Nexus Forge 后端 API 文档

                                ## 认证
                                除 `/api/auth/login` 与 `/api/auth/register` 外，所有接口需在请求头携带:
                                ```
                                Authorization: Bearer `<jwt>`
                                ```

                                ## 错误响应
                                所有错误返回 `Result<Void>` 格式:
                                ```json
                                { "code": 1001, "message": "参数校验失败", "data": null }
                                ```
                                """)
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("Nexus Forge Team")
                                .url("https://github.com/pexijl/nexus-forge-manbo"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                // 全局声明需要 Bearer 认证
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                // 定义认证方案
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("在 value 框粘贴 JWT 即可（不含 'Bearer ' 前缀）")));
    }
}
