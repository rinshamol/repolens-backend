package com.repolens.repolens_backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthProxyController {

    @Value("${spring.security.oauth2.client.registration.github.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret}")
    private String clientSecret;

    // We use a dedicated WebClient for this to avoid conflicts with your GitHub API client base URL
    private final WebClient authWebClient;
    public AuthProxyController(@Qualifier("authWebClient") WebClient authWebClient) {
        this.authWebClient = authWebClient;
    }
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Map>> proxyGithubToken(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @RequestParam String redirect_uri) {

        log.info("🔄 Proxying token request for GitHub...");

        return authWebClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .body(BodyInserters.fromFormData("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("code", code)
                        .with("state", state != null ? state : "")
                        .with("redirect_uri", redirect_uri)
                        .with("grant_type", "authorization_code"))
                .retrieve()
                .toEntity(Map.class)
                .doOnSuccess(res -> {
                    log.info("✅ Token exchanged successfully");
                    log.debug("Response status: {}", res.getStatusCode());
                })
                .doOnError(err -> {
                    log.error("❌ Token exchange failed: {}", err.getMessage());
                })
                .onErrorResume(err -> Mono.just(
                        ResponseEntity.status(500).body(
                                Map.of("error", "token_exchange_failed",
                                        "message", err.getMessage())
                        )
                ));
    }
}