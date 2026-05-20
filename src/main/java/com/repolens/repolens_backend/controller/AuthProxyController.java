package com.repolens.repolens_backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthProxyController {
    @Value("${app.frontend-base-url}")
    private String frontendUrl;
    @Value("${spring.security.oauth2.client.registration.github.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret}")
    private String clientSecret;

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> proxyGithubToken(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @RequestParam String redirect_uri) {

        log.info("🔄 Proxying token request for GitHub...");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        RestTemplate restTemplate = new RestTemplate(factory);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("code", code);
        params.add("redirect_uri", redirect_uri);
        params.add("grant_type", "authorization_code");
        if (state != null) params.add("state", state);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        log.info(">>> POST https://github.com/login/oauth/access_token");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://github.com/login/oauth/access_token",
                HttpMethod.POST,
                request,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        String accessToken = (String) response.getBody().get("access_token");
        log.info("✅ Token exchanged, redirecting to frontend");

        return ResponseEntity.status(302)
                .header("Location", frontendUrl + "?token=" + accessToken)
                .build();
    }
}