package com.repolens.repolens_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.Scopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RepoLens API")
                        .version("1.0.0")
                        .description("Analyze GitHub repositories with AI")
                        .contact(new Contact()
                                .name("RepoLens Support")
                                .url("https://github.com/repolens")))

                // ✅ STEP 1: GLOBAL SECURITY REQUIREMENT
                // This tells Swagger: "For every API call, look for the 'github_oauth' scheme"
                .addSecurityItem(new SecurityRequirement().addList("github_oauth"))

                .components(new Components()
                        // ✅ STEP 2: DEFINE THE SCHEME
                        .addSecuritySchemes("github_oauth", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("GitHub OAuth2 Authorization Code Flow")
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl("https://github.com/login/oauth/authorize")
                                                // Using relative path for the proxy we built
                                                .tokenUrl("/api/auth/token")
                                                .scopes(new Scopes()
                                                        .addString("repo", "Access private and public repositories")
                                                        .addString("user", "Read user profile info"))))));
    }
}