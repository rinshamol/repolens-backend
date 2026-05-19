package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenRouterService {

    private final ObjectMapper objectMapper;
    private final WebClient openRouterWebClient;

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.model:openai/gpt-4}")
    private String model;

    @Value("${openrouter.api.timeout:60}")
    private int timeoutSeconds;

    @Value("${openrouter.api.max-retries:3}")
    private int maxRetries;

    public OpenRouterService(ObjectMapper objectMapper, WebClient openRouterWebClient) {
        this.objectMapper = objectMapper;
        this.openRouterWebClient = openRouterWebClient;
    }

    /**
     * Analyze repository with retry logic and caching
     */
    @Retryable(
            retryFor = {WebClientResponseException.ServiceUnavailable.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    @Cacheable(value = "repositoryAnalysis", key = "#repoName + '-' + #p0.hashCode()")
    public String analyzeRepository(String repositoryCode, String repoName, String repoDescription) {
        log.info("Analyzing repository with OpenRouter: {}", repoName);

        if (!isApiKeyConfigured()) {
            log.warn("OpenRouter API key not configured! Using mock data.");
            return "{\"error\": \"AI_TIMEOUT\", \"message\": \"The AI took too long to respond. Please try again.\"}";
        }

        String truncatedCode = truncateCode(repositoryCode, 50000);
        String prompt = buildComprehensiveAnalysisPrompt(truncatedCode, repoName, repoDescription);
        return callOpenRouterAPI(prompt);
    }



    /**
     * Call OpenRouter API with proper error handling and retry logic
     */
    @Retryable(
            retryFor = {WebClientResponseException.ServiceUnavailable.class, WebClientResponseException.TooManyRequests.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 3000, multiplier = 1.5)
    )
    private String callOpenRouterAPI(String prompt) {
        Map<String, Object> requestBody = buildRequestBody(prompt);


            String url = "https://openrouter.ai/api/v1/chat/completions";

            log.debug("Calling OpenRouter API with model: {}", model);

            String response = openRouterWebClient
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://repolens.com")
                    .header("X-Title", "RepoLens")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    // ✅ INCREASE TIMEOUT: Give the AI enough time to think
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    // ✅ SIMPLIFY RETRY: Only retry on actual network/server issues
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(5))
                            .filter(throwable -> throwable instanceof WebClientResponseException.ServiceUnavailable
                                    || throwable instanceof java.util.concurrent.TimeoutException)
                            .doBeforeRetry(signal -> log.warn("Retrying OpenRouter... Attempt: {}", signal.totalRetries() + 1))
                    )
                    .block();

            log.info("✅ Received response from OpenRouter API");
            return extractTextFromResponse(response);


    }

    /**
     * Build comprehensive analysis prompt with all new fields
     * NOTE: All % characters are escaped to %% to prevent format string errors
     */
    private String buildComprehensiveAnalysisPrompt(String code, String repoName, String description) {
        String prompt = "Analyze repository: " + repoName + "\n" +
                "Description: " + (description != null && !description.isEmpty() ? description : "Repository") + "\n\n" +
                "Code Files:\n" +
                code + "\n\n" +
                "Provide ONLY valid JSON response (no markdown, no code fences):\n" +
                "{\n" +
                "  \"projectStatus\": \"Complete|In Progress|Incomplete\",\n" +
                "  \"completionPercentage\": <0-100>,\n" +
                "  \"summary\": \"2-3 sentence summary\",\n" +
                "  \"strengths\": [\n" +
                "    \"SPECIFIC strength description\"\n" +
                "  ],\n" +
                "  \"improvements\": [\n" +
                "    {\n" +
                "      \"id\": \"imp_001\",\n" +
                "      \"description\": \"ACTIONABLE improvement\",\n" +
                "      \"effortLevel\": \"Easy|Medium|Hard\",\n" +
                "      \"estimatedHours\": <number>,\n" +
                "      \"estimatedDays\": <number>,\n" +
                "      \"category\": \"Security|Testing|Performance|Documentation|Reliability\",\n" +
                "      \"impact\": \"Business impact\",\n" +
                "      \"priority\": \"Critical|High|Medium|Low\",\n" +
                "      \"tags\": [\"tag1\", \"tag2\"]\n" +
                "    }\n" +
                "  ],\n" +
                "  \"suggestedUpdates\": [\n" +
                "    {\n" +
                "      \"id\": \"upd_001\",\n" +
                "      \"packageName\": \"package-name\",\n" +
                "      \"currentVersion\": \"X.Y.Z\",\n" +
                "      \"recommendedVersion\": \"A.B.C\",\n" +
                "      \"latestVersion\": \"A.B.C\",\n" +
                "      \"releaseDate\": \"YYYY-MM-DD\",\n" +
                "      \"changelog\": \"URL\",\n" +
                "      \"priority\": \"Critical|High|Medium|Low\",\n" +
                "      \"breakingChanges\": true|false,\n" +
                "      \"estimatedHours\": <number>,\n" +
                "      \"reason\": \"Why upgrade\",\n" +
                "      \"impact\": \"Security|Performance|Features|Stability\",\n" +
                "      \"riskLevel\": \"Low|Medium|High\",\n" +
                "      \"tested\": true|false\n" +
                "    }\n" +
                "  ],\n" +
                "  \"techStack\": {\n" +
                "    \"languages\": [\n" +
                "      {\n" +
                "        \"name\": \"C#|Java|Python|JavaScript|TypeScript|Go|Rust|etc\",\n" +
                "        \"version\": \"Detected version (e.g., 12.0, 11, 3.11)\",\n" +
                "        \"releaseDate\": \"YYYY-MM-DD\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"frameworks\": [\n" +
                "      {\n" +
                "        \"name\": \"ASP.NET Core|Spring Boot|Django|Express|etc\",\n" +
                "        \"version\": \"Detected version (e.g., 8.0, 3.2.0, 4.0)\",\n" +
                "        \"releaseDate\": \"YYYY-MM-DD\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"libraries\": [\n" +
                "      {\n" +
                "        \"name\": \"LibraryName\",\n" +
                "        \"version\": \"X.Y.Z\",\n" +
                "        \"releaseDate\": \"YYYY-MM-DD\",\n" +
                "        \"purpose\": \"What it does (e.g., API Documentation, ORM, Testing)\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"buildTool\": {\n" +
                "      \"name\": \"Maven|Gradle|npm|dotnet CLI|etc\",\n" +
                "      \"version\": \"Detected version\",\n" +
                "      \"releaseDate\": \"YYYY-MM-DD\"\n" +
                "    },\n" +
                "    \"architecturePatterns\": [\"MVC\", \"Repository Pattern\", \"Dependency Injection\"],\n" +
                "    \"targetPlatform\": \".NET 8|JVM 21|Node.js 20|etc\"\n" +
                "  },\n" +
                "  \"codeQuality\": {\n" +
                "    \"rating\": \"Excellent|Good|Fair|Needs Improvement\",\n" +
                "    \"issues\": [\"SPECIFIC issue\"],\n" +
                "    \"bestPractices\": [\"SPECIFIC practice\"],\n" +
                "    \"estimatedTestCoverage\": <0-100>\n" +
                "  },\n" +
                "  \"riskAssessment\": {\n" +
                "    \"overallRiskLevel\": \"Critical|High|Medium|Low\",\n" +
                "    \"risks\": [\n" +
                "      {\n" +
                "        \"issue\": \"SPECIFIC risk\",\n" +
                "        \"severity\": \"Critical|High|Medium|Low\",\n" +
                "        \"mitigation\": \"HOW to mitigate\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}\n\n" +
                "CRITICAL REQUIREMENTS:\n" +
                "1. Return ONLY valid JSON - no markdown, no explanations\n" +
                "2. Include VERSION NUMBERS for languages, frameworks, libraries, and build tools\n" +
                "3. Include RELEASE DATES in YYYY-MM-DD format\n" +
                "4. Include PURPOSE/DESCRIPTION for each library\n" +
                "5. BE SPECIFIC AND TECHNICAL - avoid generic phrases\n" +
                "6. All numbers and percentages must be realistic\n" +
                "7. All categories and priorities must match the provided options\n" +
                "8. Include architecture patterns detected in the code\n" +
                "9. Specify target platform (.NET 8, JVM 21, Node.js 20, etc)\n";

        return prompt;
    }


    /**
     * Build OpenRouter API request body
     */
    private Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are an expert code reviewer, software architect, and security analyst. " +
                                        "Provide comprehensive, detailed analysis with specific technical recommendations. " +
                                        "Always respond with valid JSON when analyzing code."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.5,
                "max_tokens", 6000
        );
    }
    /**
     * Extract text from OpenRouter response
     */
    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            log.info("🤖 Model used: {}", root.path("model").asText("unknown"));

            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText();

                if (content.isEmpty()) {
                    log.error("Empty content in OpenRouter response");
                    throw new RuntimeException("Empty response from API");
                }

                // Clean markdown code fences if model wraps JSON
                content = content.trim();
                if (content.startsWith("```")) {
                    content = content.replaceAll("^```[a-zA-Z]*\\n?", "")
                            .replaceAll("```$", "")
                            .trim();
                }

                log.debug("Extracted {} characters from OpenRouter response", content.length());
                return content;
            }

            throw new RuntimeException("Invalid OpenRouter response format: no choices found");

        } catch (Exception e) {
            log.error("Error parsing OpenRouter response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse OpenRouter response", e);
        }
    }


    /**
     * Truncate code to avoid token limits
     */
    private String truncateCode(String code, int maxCharacters) {
        if (code.length() > maxCharacters) {
            log.warn("Code truncated from {} to {} characters", code.length(), maxCharacters);
            return code.substring(0, maxCharacters) + "\n... (truncated for size)";
        }
        return code;
    }

    /**
     * Check if API key is configured
     */
    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your-key-here");
    }





}