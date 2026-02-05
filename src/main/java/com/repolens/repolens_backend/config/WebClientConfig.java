package com.repolens.repolens_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean(name = "githubWebClient")
    public WebClient githubWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Bean(name = "geminiWebClient")
    public WebClient geminiWebClient() {
        return WebClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean(name = "deepseekWebClient")
    public WebClient deepseekWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.deepseek.com/v1") // DeepSeek API base URL
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public WebClient openRouterWebClient() {
        return WebClient.builder()
                .baseUrl("https://openrouter.ai/api/v1/chat/completions")
                .defaultHeader("Authorization", "Bearer " + System.getenv("OPENROUTER_API_KEY"))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }



    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}