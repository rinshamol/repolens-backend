package com.repolens.repolens_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean("githubWebClient")
     public WebClient githubWebClient() {
        // Setting up the engine with a 15-second "patience" limit
        HttpClient httpClient = HttpClient.create()
                .secure()
                .responseTimeout(Duration.ofSeconds(15));

        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 1. Mandatory to avoid 403 Forbidden
                .defaultHeader("User-Agent", "RepoLens-App")
                // 2. Mandatory for data stability (so your code doesn't break later)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                // 3. Standard for receiving JSON
                .defaultHeader("Accept", "application/vnd.github+json")
                // 4. Helpful for debugging
                .filter(logRequest())
                .build();
    }





    @Bean("openRouterWebClient")
    public WebClient openRouterWebClient() {
        HttpClient httpClient = HttpClient.create()
                .secure()
                .responseTimeout(Duration.ofSeconds(120))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);

        return WebClient.builder()
                .baseUrl("https://openrouter.ai/api/v1") // Keep base URL short
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + System.getenv("OPENROUTER_API_KEY"))
                .defaultHeader("Content-Type", "application/json")
                .filter(logRequest())
                .build();
    }

    @Bean("authWebClient")
    public WebClient authWebClient() {
        HttpClient httpClient = HttpClient.create()
                .secure()
                .responseTimeout(Duration.ofSeconds(30));

        return WebClient.builder()
                .baseUrl("https://github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .filter(logRequest())
                .build();
    }

    /**
     * Log HTTP requests
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            System.out.println(">>> " + clientRequest.method() + " " + clientRequest.url());
            clientRequest.headers()
                    .forEach((name, values) -> values.forEach(value ->
                            System.out.println(name + ": " + value)));
            return reactor.core.publisher.Mono.just(clientRequest);
        });
    }

    /**
     * Log HTTP responses
     */
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            System.out.println("<<< " + clientResponse.statusCode());
            clientResponse.headers()
                    .asHttpHeaders()
                    .forEach((name, values) -> values.forEach(value ->
                            System.out.println(name + ": " + value)));
            return reactor.core.publisher.Mono.just(clientResponse);
        });
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}