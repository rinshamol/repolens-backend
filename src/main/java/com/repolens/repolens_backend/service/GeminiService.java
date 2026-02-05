package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    private final WebClient geminiWebClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")  // Add default empty string
    private String apiKey;

    @Value("${gemini.api.model:gemini-2.0-flash-exp}")  // Add default model
    private String model;

    // Constructor with @Qualifier
    public GeminiService(
            @Qualifier("geminiWebClient") WebClient geminiWebClient,
            ObjectMapper objectMapper) {
        this.geminiWebClient = geminiWebClient;
        this.objectMapper = objectMapper;
    }

    public String analyzeRepository(String repositoryCode, String repoName, String repoDescription) {
        log.info("Analyzing repository with Gemini: {}", repoName);

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API key not configured!");
            return createMockAnalysis(repoName);
        }

        String prompt = buildAnalysisPrompt(repositoryCode, repoName, repoDescription);

        return callGeminiAPI(prompt, 4096);
    }

    public String generateUi(String repositoryCode, String techStack) {
        log.info("Generating UI mockup with Gemini");

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("Gemini API key not configured!");
            return createMockUI();
        }

        String prompt = buildUiGenerationPrompt(repositoryCode, techStack);

        return callGeminiAPI(prompt, 8192);
    }

    private String callGeminiAPI(String prompt, int maxTokens) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "topK", 40,
                        "topP", 0.95,
                        "maxOutputTokens", maxTokens
                )
        );

        try {
            String response = geminiWebClient
                    .post()
                    .uri("/models/{model}:generateContent?key={apiKey}", model, apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return extractTextFromResponse(response);

        } catch (WebClientResponseException e) {
            log.error("Gemini API error - Status: {}, Body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to call Gemini API: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error calling Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to analyze repository with Gemini", e);
        }
    }

    private String buildAnalysisPrompt(String code, String repoName, String description) {
        String truncatedCode = code.length() > 30000 ?
                code.substring(0, 30000) + "\n\n...(code truncated due to length)" : code;

        return String.format("""
            You are an expert code reviewer analyzing a GitHub repository.
            
            Repository: %s
            Description: %s
            
            Code Files:
            %s
            
            Analyze this repository and provide a comprehensive review in JSON format ONLY.
            
            Your response must be ONLY valid JSON with this exact structure (no markdown, no code fences):
            
            {
              "projectStatus": "Complete|In Progress|Incomplete",
              "completionPercentage": 0-100,
              "summary": "Brief overview",
              "strengths": ["strength1", "strength2", "strength3"],
              "improvements": ["improvement1", "improvement2"],
              "suggestedUpdates": ["update1", "update2"],
              "techStack": {
                "languages": ["language1"],
                "frameworks": ["framework1"],
                "libraries": ["lib1"],
                "buildTool": "Maven|Gradle|npm|etc"
              },
              "codeQuality": {
                "rating": "Excellent|Good|Fair|Needs Improvement",
                "issues": ["issue1"],
                "bestPractices": ["practice1"]
              }
            }
            
            Return ONLY the JSON object. Start with { and end with }. No markdown.
            """,
                repoName,
                description != null && !description.isEmpty() ? description : "No description",
                truncatedCode
        );
    }

    private String buildUiGenerationPrompt(String code, String techStack) {
        String truncatedCode = code.length() > 10000 ?
                code.substring(0, 10000) + "\n...(truncated)" : code;

        return String.format("""
            Based on this codebase, generate a sample HTML UI mockup.
            
            Tech Stack: %s
            
            Code:
            %s
            
            Generate a complete HTML file with embedded CSS and minimal JavaScript.
            Make it modern and professional.
            Use Tailwind CSS via CDN.
            
            Return ONLY the HTML code. No markdown.
            Start with <!DOCTYPE html> and end with </html>.
            """,
                techStack,
                truncatedCode
        );
    }

    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.path("candidates");

            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.path("content");
                JsonNode parts = content.path("parts");

                if (parts.isArray() && parts.size() > 0) {
                    String text = parts.get(0).path("text").asText();
                    log.debug("Extracted response length: {} characters", text.length());
                    return text;
                }
            }

            log.error("Unexpected Gemini response format: {}", response);
            throw new RuntimeException("Invalid Gemini API response format");

        } catch (Exception e) {
            log.error("Error parsing Gemini response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse Gemini response", e);
        }
    }

    // Mock response for testing without API key
    private String createMockAnalysis(String repoName) {
        return """
            {
              "projectStatus": "Complete",
              "completionPercentage": 85,
              "summary": "Mock analysis for %s (Gemini API key not configured)",
              "strengths": ["Clean code structure", "Good documentation"],
              "improvements": ["Add more tests"],
              "suggestedUpdates": ["Update dependencies"],
              "techStack": {
                "languages": ["Java"],
                "frameworks": ["Spring Boot"],
                "libraries": [],
                "buildTool": "Maven"
              },
              "codeQuality": {
                "rating": "Good",
                "issues": [],
                "bestPractices": ["Uses dependency injection"]
              }
            }
            """.formatted(repoName);
    }

    private String createMockUI() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Mock UI</title>
            </head>
            <body>
                <h1>Mock UI (Gemini API key not configured)</h1>
            </body>
            </html>
            """;
    }
}