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
            return createMockAnalysis(repoName);
        }

        String truncatedCode = truncateCode(repositoryCode, 50000);
        String prompt = buildComprehensiveAnalysisPrompt(truncatedCode, repoName, repoDescription);
        return callOpenRouterAPI(prompt);
    }

    /**
     * Generate UI with fallback
     */
    public String generateUi(String repositoryCode, String techStack) {
        log.info("Generating UI mockup with OpenRouter");

        if (!isApiKeyConfigured()) {
            log.warn("OpenRouter API key not configured! Using mock UI.");
            return createMockUI();
        }

        String truncatedCode = truncateCode(repositoryCode, 5000);
        String prompt = buildUiGenerationPrompt(truncatedCode, techStack);

        try {
            return callOpenRouterAPI(prompt);
        } catch (Exception e) {
            log.error("UI generation failed, returning mock UI: {}", e.getMessage());
            return createMockUI();
        }
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

        try {
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
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2))
                            .maxBackoff(Duration.ofSeconds(30))
                            .doBeforeRetry(signal -> log.warn("Retrying API call, attempt: {}", signal.totalRetries() + 1))
                    )
                    .block();

            log.info("✅ Received response from OpenRouter API");
            return extractTextFromResponse(response);

        } catch (WebClientResponseException e) {
            return handleApiError(e);
        } catch (Exception e) {
            log.error("Unexpected error calling OpenRouter API: {}", e.getMessage(), e);
            return createMockAnalysis("repository");
        }
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
     * Build UI generation prompt
     */
    private String buildUiGenerationPrompt(String code, String techStack) {
        return "Generate a professional HTML UI mockup for this application.\n\n" +
                "Tech Stack: " + techStack + "\n\n" +
                "Code Sample:\n" +
                code + "\n\n" +
                "Requirements:\n" +
                "1. Complete, valid HTML with embedded CSS (no external sheets)\n" +
                "2. Use Tailwind CSS from CDN\n" +
                "3. Modern, professional design with realistic data\n" +
                "4. Responsive and mobile-friendly\n" +
                "5. Include sample screens based on the code structure\n\n" +
                "Return ONLY the HTML code. No markdown, no explanations.\n" +
                "Start with <!DOCTYPE html> and end with </html>";
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
                "max_tokens", 50000,
                "top_p", 0.9
        );
    }

    /**
     * Extract text from OpenRouter response
     */
    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");

            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText();

                if (content.isEmpty()) {
                    log.error("Empty content in OpenRouter response");
                    throw new RuntimeException("Empty response from API");
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
     * Handle API errors with specific strategies
     */
    private String handleApiError(WebClientResponseException e) {
        int status = e.getStatusCode().value();

        switch (status) {
            case 429:
                log.warn("⚠️ Rate limit exceeded (429). Using mock data.");
                return createMockAnalysis("repository");

            case 401:
            case 403:
                log.error("❌ Authentication failed ({}). Check API key.", status);
                throw new RuntimeException("Invalid OpenRouter API key");

            case 503:
            case 502:
                log.warn("⚠️ OpenRouter service unavailable ({}). Retrying...", status);
                throw e;


            case 500:
                log.error("❌ Server error (500): {}", e.getResponseBodyAsString());
                return createMockAnalysis("repository");

            default:
                log.error("API error {}: {}", status, e.getResponseBodyAsString());
                return createMockAnalysis("repository");
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

    /**
     * Create mock analysis for testing/fallback
     */
    private String createMockAnalysis(String repoName) {
        return """
        {
          "projectStatus": "In Progress",
          "completionPercentage": 75,
          "summary": "Well-structured application with modern development practices. The codebase demonstrates good separation of concerns and follows industry standards.",
          "strengths": [
            "Clean layered architecture with Controller-Service-Repository pattern",
            "Comprehensive error handling with custom exceptions",
            "Proper use of dependency injection and Spring conventions",
            "RESTful API design following HTTP best practices"
          ],
          "improvements": [
            {
              "id": "imp_001",
              "description": "Add unit tests for service layer with JUnit 5 and Mockito",
              "effortLevel": "Medium",
              "estimatedHours": 8.0,
              "estimatedDays": 1,
              "category": "Testing",
              "impact": "Increases code reliability",
              "priority": "High",
              "tags": ["testing", "quality"]
            },
            {
              "id": "imp_002",
              "description": "Implement integration tests for API endpoints using TestRestTemplate",
              "effortLevel": "Medium",
              "estimatedHours": 6.0,
              "estimatedDays": 1,
              "category": "Testing",
              "impact": "Catches regressions early",
              "priority": "High",
              "tags": ["testing", "integration"]
            }
          ],
          "suggestedUpdates": [
            {
              "id": "upd_001",
              "packageName": "spring-boot",
              "currentVersion": "3.0.0",
              "recommendedVersion": "3.2.0",
              "latestVersion": "3.2.0",
              "releaseDate": "2024-01-15",
              "changelog": "https://spring.io/blog",
              "priority": "High",
              "breakingChanges": false,
              "estimatedHours": 1.5,
              "reason": "Security patches and performance improvements",
              "impact": "Security",
              "riskLevel": "Low",
              "tested": true
            }
          ],
          "techStack": {
            "languages": ["Java"],
            "frameworks": ["Spring Boot 3.0"],
            "libraries": ["Spring Data JPA", "Spring Security", "Lombok"],
            "buildTool": "Maven"
          },
          "codeQuality": {
            "rating": "Good",
            "issues": [
              "Some methods exceed recommended line count",
              "Missing null safety checks in some areas"
            ],
            "bestPractices": [
              "Uses @Service and @Repository annotations effectively",
              "Proper exception hierarchy and handling",
              "Clean method naming conventions"
            ],
            "estimatedTestCoverage": 35
          },
          "riskAssessment": {
            "overallRiskLevel": "Medium",
            "risks": [
              {
                "issue": "Missing authentication on some API endpoints",
                "severity": "High",
                "mitigation": "Apply @PreAuthorize annotations and test security rules"
              }
            ]
          }
        }
        """;
    }

    /**
     * Create mock UI for testing/fallback
     */
    private String createMockUI() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>RepoLens - Code Review Preview</title>
            <script src="https://cdn.tailwindcss.com"></script>
        </head>
        <body class="bg-gray-50">
            <nav class="bg-white shadow">
                <div class="max-w-7xl mx-auto px-4 py-4">
                    <h1 class="text-2xl font-bold text-blue-600">RepoLens</h1>
                </div>
            </nav>
            
            <div class="max-w-6xl mx-auto p-6">
                <div class="bg-white rounded-lg shadow-lg p-8">
                    <h2 class="text-3xl font-bold mb-4 text-gray-900">Code Review Analysis</h2>
                    <p class="text-gray-600 mb-8">
                        This is a preview of the AI-generated analysis. Configure your OpenRouter API key
                        to generate detailed reviews of your repositories.
                    </p>
                    
                    <div class="grid grid-cols-2 gap-6 mb-8">
                        <div class="bg-blue-50 border-l-4 border-blue-500 p-4">
                            <h3 class="font-semibold text-blue-900 mb-2">Quick Stats</h3>
                            <p class="text-sm text-blue-700">Comprehensive code analysis powered by AI</p>
                        </div>
                        <div class="bg-green-50 border-l-4 border-green-500 p-4">
                            <h3 class="font-semibold text-green-900 mb-2">Real-time Review</h3>
                            <p class="text-sm text-green-700">Get actionable recommendations instantly</p>
                        </div>
                    </div>
                </div>
            </div>
        </body>
        </html>
        """;
    }
}