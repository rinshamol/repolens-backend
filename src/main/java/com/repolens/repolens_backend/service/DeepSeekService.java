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
public class DeepSeekService {

    private final ObjectMapper objectMapper;

    @Value("${deepseek.api.key:}")
    private String apiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String model;

    public DeepSeekService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String analyzeRepository(String repositoryCode, String repoName, String repoDescription) {
        log.info("Analyzing repository with DeepSeek: {}", repoName);

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DeepSeek API key not configured! Using mock data.");
            return createMockAnalysis(repoName);
        }

        String prompt = buildAnalysisPrompt(repositoryCode, repoName, repoDescription);
        return callDeepSeekAPI(prompt);
    }

    public String generateUi(String repositoryCode, String techStack) {
        log.info("Generating UI mockup with DeepSeek");

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("DeepSeek API key not configured! Using mock UI.");
            return createMockUI();
        }

        String prompt = buildUiGenerationPrompt(repositoryCode, techStack);
        return callDeepSeekAPI(prompt);
    }

    private String callDeepSeekAPI(String prompt) {
        // DeepSeek uses OpenAI-compatible API
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are an expert code reviewer and software architect."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.7,
                "max_tokens", 4000
        );

        try {
            String url = "https://api.deepseek.com/v1/chat/completions";

            log.info("Calling DeepSeek API...");

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

            log.info("✅ Received response from DeepSeek API");
            return extractTextFromResponse(response);

        } catch (WebClientResponseException e) {
            log.error("DeepSeek API error - Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());

            if (e.getStatusCode().value() == 429) {
                log.warn("⚠️ Rate limit exceeded. Using mock data.");
            } else if (e.getStatusCode().value() == 401) {
                log.error("❌ Invalid API key!");
            }

            return createMockAnalysis("repository");

        } catch (Exception e) {
            log.error("Error calling DeepSeek API: {}", e.getMessage(), e);
            return createMockAnalysis("repository");
        }
    }

    private String buildAnalysisPrompt(String code, String repoName, String description) {
        // Limit code to 10,000 characters to save tokens
        String truncatedCode = code.length() > 10000 ?
                code.substring(0, 10000) + "\n\n...(code truncated to save API tokens)" : code;

        return String.format("""
            Analyze this GitHub repository and provide a comprehensive code review.
            
            Repository Name: %s
            Description: %s
            
            Code Files:
            %s
            
            Provide your analysis as a JSON object with this EXACT structure (no markdown, no code fences, no explanations):
            
            {
              "projectStatus": "Complete",
              "completionPercentage": 88,
              "summary": "Detailed summary of the project and its purpose",
              "strengths": [
                "Specific strength 1",
                "Specific strength 2",
                "Specific strength 3"
              ],
              "improvements": [
                "Specific improvement 1",
                "Specific improvement 2"
              ],
              "suggestedUpdates": [
                "Specific update recommendation 1",
                "Specific update recommendation 2"
              ],
              "techStack": {
                "languages": ["Java", "JavaScript"],
                "frameworks": ["Spring Boot", "React"],
                "libraries": ["Lombok", "Jackson"],
                "buildTool": "Maven"
              },
              "codeQuality": {
                "rating": "Excellent",
                "issues": ["Specific issue if any"],
                "bestPractices": ["Specific best practice observed"]
              }
            }
            
            IMPORTANT: Return ONLY the JSON object. No markdown formatting, no code blocks, no additional text.
            Start your response with { and end with }
            """,
                repoName,
                description != null && !description.isEmpty() ? description : "No description provided",
                truncatedCode
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

    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");

            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText();

                log.debug("Extracted {} characters from DeepSeek response", content.length());
                return content;
            }

            log.error("Unexpected DeepSeek response format: {}", response);
            throw new RuntimeException("Invalid DeepSeek response format");

        } catch (Exception e) {
            log.error("Error parsing DeepSeek response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse DeepSeek response", e);
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
                            This is a placeholder UI mockup. Configure DeepSeek API key to generate 
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