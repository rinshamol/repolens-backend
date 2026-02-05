package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenRouterService {

    private final ObjectMapper objectMapper;

    @Value("${openrouter.api.key:}")
    private String apiKey;

    @Value("${openrouter.api.model:openai/gpt-4}")
    private String model;

    public OpenRouterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String analyzeRepository(String repositoryCode, String repoName, String repoDescription) {
        log.info("Analyzing repository with OpenRouter: {}", repoName);

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenRouter API key not configured! Using mock data.");
            return createMockAnalysis(repoName);
        }

        String prompt = buildAnalysisPrompt(repositoryCode, repoName, repoDescription);
        return callOpenRouterAPI(prompt);
    }

    public String generateUi(String repositoryCode, String techStack) {
        log.info("Generating UI mockup with OpenRouter");

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenRouter API key not configured! Using mock UI.");
            return createMockUI();
        }

        String prompt = buildUiGenerationPrompt(repositoryCode, techStack);
        return callOpenRouterAPI(prompt);
    }

    private String callOpenRouterAPI(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are an expert code reviewer and software architect."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.7,
                "max_tokens", 10000
        );

        try {
            String url = "https://openrouter.ai/api/v1/chat/completions";

            log.info("Calling OpenRouter API...");

            String response = WebClient.builder()
                    .build()
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Received response from OpenRouter API");
            return extractTextFromResponse(response);

        } catch (WebClientResponseException e) {
            log.error("OpenRouter API error - Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());

            if (e.getStatusCode().value() == 429) {
                log.warn("⚠️ Rate limit exceeded. Using mock data.");
            } else if (e.getStatusCode().value() == 401) {
                log.error("❌ Invalid API key!");
            }

            return createMockAnalysis("repository");

        } catch (Exception e) {
            log.error("Error calling OpenRouter API: {}", e.getMessage(), e);
            return createMockAnalysis("repository");
        }
    }
    private String buildAnalysisPrompt(String code, String repoName, String description) {
        return String.format("""
        Analyze repository: %s
        %s
        
        Files:
        %s
        
        Provide detailed JSON analysis (no markdown):
        {
          "projectStatus": "Complete|In Progress|Incomplete",
          "completionPercentage": 85,
          "summary": "Specific project purpose and implementation details in 2-3 sentences",
          "strengths": [
            "SPECIFIC strength with technical details (e.g., 'Uses React Navigation v6 for type-safe routing')",
            "SPECIFIC architectural decision (e.g., 'Implements AsyncStorage for offline data persistence')",
            "SPECIFIC code quality aspect (e.g., 'Modular component structure with custom hooks')"
          ],
          "improvements": [
            "ACTIONABLE improvement with HOW (e.g., 'Add error boundaries to handle runtime errors gracefully')",
            "SPECIFIC enhancement (e.g., 'Implement TypeScript strict mode for better type safety')"
          ],
          "suggestedUpdates": [
            "SPECIFIC update with version (e.g., 'Update Expo SDK from 49 to 50 for performance improvements')",
            "TECHNICAL recommendation (e.g., 'Add ESLint with Airbnb config for code consistency')"
          ],
          "techStack": {
            "languages": ["Detect from package.json"],
            "frameworks": ["Exact framework names with versions if visible"],
            "libraries": ["Key libraries like react-navigation, axios, etc."],
            "buildTool": "Detect from package.json: npm/yarn/expo"
          },
          "codeQuality": {
            "rating": "Excellent|Good|Fair|Needs Improvement",
            "issues": ["SPECIFIC issue (e.g., 'Missing PropTypes validation in Button component')"],
            "bestPractices": ["SPECIFIC practice (e.g., 'Uses functional components with hooks throughout')"]
          }
        }
        
        BE SPECIFIC AND TECHNICAL. Avoid generic phrases like "improve error handling" - instead say "Add try-catch blocks in async API calls" or "Implement error boundary for component crashes".
        """,
                repoName,
                description != null && !description.isEmpty() ? description : "Medical reminder app",
                code
        );
    }

    private String buildUiGenerationPrompt(String code, String techStack) {
        String truncatedCode = code.length() > 5000 ?
                code.substring(0, 5000) + "\n...(truncated)" : code;

        return String.format("""
        Generate a professional HTML UI mockup for this application.
        
        Tech Stack: %s
        
        Code Sample:
        %s
        
        Requirements:
        1. Create a complete HTML page with embedded CSS
        2. Make it modern, clean, and professional
        3. Use Tailwind CSS via CDN for styling
        4. Include realistic placeholder content
        5. Make it responsive (mobile-friendly)
        
        Return ONLY the HTML code. No markdown, no explanations.
        Start with <!DOCTYPE html> and end with </html>
        """,
                techStack,
                truncatedCode
        );
    }
    private String detectBuildTool(String code) {
        if (code.contains("\"expo\"")) return "Expo CLI";
        if (code.contains("yarn.lock")) return "Yarn";
        if (code.contains("package-lock.json")) return "npm";
        if (code.contains("pnpm-lock.yaml")) return "pnpm";
        if (code.contains("pom.xml")) return "Maven";
        if (code.contains("build.gradle")) return "Gradle";
        if (code.contains("requirements.txt")) return "pip";
        if (code.contains("Cargo.toml")) return "Cargo";
        return "npm"; // default for package.json
    }
    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");

            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText();

                log.debug("Extracted {} characters from OpenRouter response", content.length());
                return content;
            }

            log.error("Unexpected OpenRouter response format: {}", response);
            throw new RuntimeException("Invalid OpenRouter response format");

        } catch (Exception e) {
            log.error("Error parsing OpenRouter response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse OpenRouter response", e);
        }
    }
    private String createMockAnalysis(String repoName) {
        return """
        {
          "projectStatus": "Complete",
          "completionPercentage": 88,
          "summary": "This is a well-structured application demonstrating modern software development practices. The codebase shows clean architecture with proper separation of concerns and follows industry standards.",
          "strengths": [
            "Clean code organization with layered architecture (Controller-Service-Repository pattern)",
            "Comprehensive error handling with custom exceptions and global exception handler",
            "Good use of Spring Boot conventions and dependency injection",
            "RESTful API design following HTTP best practices"
          ],
          "improvements": [
            "Increase unit test coverage to 80%+ for better code reliability",
            "Add integration tests for API endpoints",
            "Implement caching strategy (Redis) for improved performance",
            "Add API rate limiting to prevent abuse"
          ],
          "suggestedUpdates": [
            "Update Spring Boot to latest stable version",
            "Add OpenAPI/Swagger documentation for better API discoverability",
            "Implement structured logging with correlation IDs",
            "Consider adding health check endpoints using Spring Boot Actuator"
          ],
          "techStack": {
            "languages": ["Java"],
            "frameworks": ["Spring Boot"],
            "libraries": ["Lombok", "Jackson"],
            "buildTool": "Maven"
          },
          "codeQuality": {
            "rating": "Good",
            "issues": [],
            "bestPractices": [
              "Uses dependency injection throughout",
              "Follows SOLID principles",
              "Implements proper exception handling"
            ]
          }
        }
        """;
    }

    private String createMockUI() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Application Preview</title>
            <script src="https://cdn.tailwindcss.com"></script>
        </head>
        <body class="bg-gray-50">
            <div class="min-h-screen flex items-center justify-center p-4">
                <div class="max-w-2xl w-full bg-white rounded-lg shadow-lg p-8">
                    <h1 class="text-3xl font-bold text-gray-900 mb-4">Mock UI Preview</h1>
                    <p class="text-gray-600 mb-6">
                        This is a placeholder UI mockup. Configure OpenRouter API key to generate
                        real UI mockups based on your code.
                    </p>
                    <div class="bg-blue-50 border border-blue-200 rounded-lg p-4">
                        <h2 class="text-lg font-semibold text-blue-900 mb-2">Features</h2>
                        <ul class="list-disc list-inside text-blue-800 space-y-1">
                            <li>Modern, responsive design</li>
                            <li>Clean user interface</li>
                            <li>Professional styling</li>
                        </ul>
                    </div>
                </div>
            </div>
        </body>
        </html>
        """;
    }

}