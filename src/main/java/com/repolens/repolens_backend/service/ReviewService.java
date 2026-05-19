package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.repolens_backend.dto.AuthorizationRequiredDto;
import com.repolens.repolens_backend.dto.ReviewRequestDto;
import com.repolens.repolens_backend.dto.ReviewResponseDto;
import com.repolens.repolens_backend.model.GitHubRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {
    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;
    private final GitHubService gitHubService;
    private final OpenRouterService openRouterService;
    private final ObjectMapper objectMapper;

    public Object reviewRepository(ReviewRequestDto request, String userGitHubToken) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("🔍 Starting repository review: {}", request.getRepoUrl());

            // Step 1: Parse URL
            String[] ownerRepo = gitHubService.parseGitHubUrl(request.getRepoUrl());
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];

            // ✅ STEP 2: CHECK ACCESSIBILITY FIRST (before metadata!)
            String accessibility = gitHubService.checkRepositoryAccessibility(owner, repo, userGitHubToken);
            log.info("📊 Repository accessibility: {}", accessibility);

            if ("INACCESSIBLE".equals(accessibility)) {
                log.info("❌ Repository is inaccessible");
                return buildErrorResponse(
                        "Repository not found or inaccessible. If this is a private repository, please authorize with GitHub.",
                        "REPOSITORY_INACCESSIBLE",
                        startTime
                );
            }

            if ("PRIVATE".equals(accessibility)) {
                if (userGitHubToken == null || userGitHubToken.isEmpty()) {
                    log.info("🔒 Private repository - user not authenticated");
                    return buildAuthorizationResponse(request.getRepoUrl());
                }
                log.info("🔐 Private repository - user authenticated");
            }

            // ✅ STEP 3: Now fetch metadata (we know we can access it)
            GitHubRepository repoData = gitHubService.getRepository(owner, repo, userGitHubToken);
            log.info("✓ Repository found: {}", repoData.getFullName());

            // ✅ STEP 4: Collect code WITH TOKEN
            String repositoryCode = gitHubService.collectRepositoryCode(owner, repo, userGitHubToken);

            if (repositoryCode.isEmpty()) {
                log.warn("⚠️ No code found in repository");
                return buildEmptyCodeResponse(repoData, startTime);
            }

            // Step 5: Analyze with AI
            String analysisJson = openRouterService.analyzeRepository(
                    repositoryCode,
                    repoData.getName(),
                    repoData.getDescription()
            );

            // Step 6: Parse analysis
            ReviewResponseDto.CodeAnalysis analysis = parseAnalysis(analysisJson);
            ReviewResponseDto.RepositoryMetadata metadata = buildMetadata(repoData);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("✅ Review completed in {}ms", processingTime);

            return ReviewResponseDto.builder()
                    .repositoryName(repoData.getFullName())
                    .repositoryUrl(repoData.getHtmlUrl())
                    .metadata(metadata)
                    .analysis(analysis)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (IllegalArgumentException e) {
            log.error("❌ Invalid input: {}", e.getMessage());
            return buildErrorResponse("Invalid GitHub URL", "INVALID_INPUT", startTime);
        } catch (Exception e) {
            log.error("❌ Error: {}", e.getMessage());
            return buildErrorResponse("Failed to review repository", "INTERNAL_ERROR", startTime);
        }
    }

    /**
     * Build authorization response - user will auto-redirect
     */
    private AuthorizationRequiredDto buildAuthorizationResponse(String repositoryUrl) {
        String state = gitHubService.encodeRepoUrl(repositoryUrl);
        String authUrl = gitHubService.generateAuthorizationUrl(repositoryUrl, state);

        return AuthorizationRequiredDto.builder()
                .type("AUTHORIZATION_REQUIRED")
                .message("This is a private repository. Redirecting to GitHub authorization...")
                .repositoryUrl(repositoryUrl)
                .authorizationUrl(authUrl)
                .redirectAfterAuth(frontendBaseUrl+"/analyze?repo=" + state)
                .autoRedirectDelayMs(2000)
                .autoRedirect(true)
                .build();
    }

    /**
     * Parse comprehensive AI analysis response including all new fields
     */
    private ReviewResponseDto.CodeAnalysis parseAnalysis(String analysisJson) {
        try {
            String cleanJson = extractJson(analysisJson);
            JsonNode jsonNode = objectMapper.readTree(cleanJson);

            return ReviewResponseDto.CodeAnalysis.builder()
                    .projectStatus(getStringValue(jsonNode, "projectStatus", "Unknown"))
                    .completionPercentage(getIntValue(jsonNode, "completionPercentage", 0))
                    .summary(getStringValue(jsonNode, "summary", "No summary available"))
                    .strengths(parseStringList(jsonNode.get("strengths")))
                    .improvements(parseImprovements(jsonNode.get("improvements")))
                    .suggestedUpdates(parseUpdates(jsonNode.get("suggestedUpdates")))
                    .techStack(parseTechStack(jsonNode))
                    .codeQuality(parseCodeQuality(jsonNode))
                    .riskAssessment(parseRiskAssessment(jsonNode))
                    .build();

        } catch (Exception e) {
            log.error("Error parsing analysis JSON: {}", e.getMessage());
            log.debug("Raw JSON: {}", analysisJson);
            return createFallbackAnalysis(analysisJson);
        }
    }

    /**
     * Parse improvements array into ImprovementItem objects
     */
    private List<ReviewResponseDto.ImprovementItem> parseImprovements(JsonNode node) {
        List<ReviewResponseDto.ImprovementItem> improvements = new ArrayList<>();

        if (node == null || !node.isArray()) {
            return improvements;
        }

        int i = 1;
        for (JsonNode item : node) {
            try {
                ReviewResponseDto.ImprovementItem improvementItem = ReviewResponseDto.ImprovementItem.builder()
                        .id(getStringValue(item, "id", "imp_" + String.format("%03d", i)))
                        .description(getStringValue(item, "description", ""))
                        .effortLevel(getStringValue(item, "effortLevel", "Medium"))
                        .estimatedHours(getDoubleValue(item, "estimatedHours", 4.0))
                        .estimatedDays(getIntValue(item, "estimatedDays", 1))
                        .category(getStringValue(item, "category", "General"))
                        .impact(getStringValue(item, "impact", ""))
                        .priority(getStringValue(item, "priority", "Medium"))
                        .tags(parseStringList(item.get("tags")))
                        .build();

                improvements.add(improvementItem);
                i++;

            } catch (Exception e) {
                log.warn("Failed to parse improvement item: {}", e.getMessage());
            }
        }

        return improvements;
    }

    /**
     * Parse updates array into UpdateItem objects
     */
    private List<ReviewResponseDto.UpdateItem> parseUpdates(JsonNode node) {
        List<ReviewResponseDto.UpdateItem> updates = new ArrayList<>();

        if (node == null || !node.isArray()) {
            return updates;
        }

        int i = 1;
        for (JsonNode item : node) {
            try {
                ReviewResponseDto.UpdateItem updateItem = ReviewResponseDto.UpdateItem.builder()
                        .id(getStringValue(item, "id", "upd_" + String.format("%03d", i)))
                        .packageName(getStringValue(item, "packageName", ""))
                        .currentVersion(getStringValue(item, "currentVersion", ""))
                        .recommendedVersion(getStringValue(item, "recommendedVersion", ""))
                        .latestVersion(getStringValue(item, "latestVersion", ""))
                        .releaseDate(getStringValue(item, "releaseDate", ""))
                        .changelog(getStringValue(item, "changelog", ""))
                        .priority(getStringValue(item, "priority", "Medium"))
                        .breakingChanges(getBooleanValue(item, "breakingChanges", false))
                        .estimatedHours(getDoubleValue(item, "estimatedHours", 1.0))
                        .reason(getStringValue(item, "reason", ""))
                        .impact(getStringValue(item, "impact", ""))
                        .riskLevel(getStringValue(item, "riskLevel", "Low"))
                        .tested(getBooleanValue(item, "tested", false))
                        .build();

                updates.add(updateItem);
                i++;

            } catch (Exception e) {
                log.warn("Failed to parse update item: {}", e.getMessage());
            }
        }

        return updates;
    }

    /**
     * Parse risk assessment
     */
    private ReviewResponseDto.RiskAssessment parseRiskAssessment(JsonNode jsonNode) {
        try {
            if (!jsonNode.has("riskAssessment")) {
                return null;
            }

            JsonNode riskNode = jsonNode.get("riskAssessment");
            List<ReviewResponseDto.Risk> risks = new ArrayList<>();

            if (riskNode.has("risks") && riskNode.get("risks").isArray()) {
                riskNode.get("risks").forEach(item -> {
                    try {
                        risks.add(ReviewResponseDto.Risk.builder()
                                .issue(getStringValue(item, "issue", ""))
                                .severity(getStringValue(item, "severity", "Medium"))
                                .mitigation(getStringValue(item, "mitigation", ""))
                                .build());
                    } catch (Exception e) {
                        log.warn("Failed to parse risk item: {}", e.getMessage());
                    }
                });
            }

            return ReviewResponseDto.RiskAssessment.builder()
                    .overallRiskLevel(getStringValue(riskNode, "overallRiskLevel", "Medium"))
                    .risks(risks)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse risk assessment: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse tech stack with versions
     */
    private ReviewResponseDto.TechStackAnalysis parseTechStack(JsonNode jsonNode) {
        if (!jsonNode.has("techStack")) {
            return createDefaultTechStack();
        }

        JsonNode techNode = jsonNode.get("techStack");

        return ReviewResponseDto.TechStackAnalysis.builder()
                .languages(parseLanguages(techNode.get("languages")))
                .frameworks(parseFrameworks(techNode.get("frameworks")))
                .libraries(parseLibraries(techNode.get("libraries")))
                .buildTool(parseBuildTool(techNode.get("buildTool")))
                .architecturePatterns(parseStringList(techNode.get("architecturePatterns")))
                .targetPlatform(getStringValue(techNode, "targetPlatform", "Unknown"))
                .build();
    }

    /**
     * Parse languages with versions
     */
    private List<ReviewResponseDto.LanguageInfo> parseLanguages(JsonNode node) {
        List<ReviewResponseDto.LanguageInfo> languages = new ArrayList<>();

        if (node == null || !node.isArray()) {
            return languages;
        }

        for (JsonNode item : node) {
            try {
                // Handle both string format (legacy) and object format (new)
                if (item.isTextual()) {
                    // Legacy format: just a string like "Java"
                    languages.add(ReviewResponseDto.LanguageInfo.builder()
                            .name(item.asText())
                            .version("Unknown")
                            .releaseDate("")
                            .build());
                } else {
                    // New format: object with name, version, releaseDate
                    languages.add(ReviewResponseDto.LanguageInfo.builder()
                            .name(getStringValue(item, "name", "Unknown"))
                            .version(getStringValue(item, "version", "Unknown"))
                            .releaseDate(getStringValue(item, "releaseDate", ""))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to parse language: {}", e.getMessage());
            }
        }

        return languages;
    }

    /**
     * Parse frameworks with versions
     */
    private List<ReviewResponseDto.FrameworkInfo> parseFrameworks(JsonNode node) {
        List<ReviewResponseDto.FrameworkInfo> frameworks = new ArrayList<>();

        if (node == null || !node.isArray()) {
            return frameworks;
        }

        for (JsonNode item : node) {
            try {
                // Handle both string format (legacy) and object format (new)
                if (item.isTextual()) {
                    // Legacy format: just a string like "Spring Boot 3.0"
                    frameworks.add(ReviewResponseDto.FrameworkInfo.builder()
                            .name(item.asText())
                            .version("Unknown")
                            .releaseDate("")
                            .build());
                } else {
                    // New format: object with name, version, releaseDate
                    frameworks.add(ReviewResponseDto.FrameworkInfo.builder()
                            .name(getStringValue(item, "name", "Unknown"))
                            .version(getStringValue(item, "version", "Unknown"))
                            .releaseDate(getStringValue(item, "releaseDate", ""))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to parse framework: {}", e.getMessage());
            }
        }

        return frameworks;
    }

    /**
     * Parse libraries with versions
     */
    private List<ReviewResponseDto.LibraryInfo> parseLibraries(JsonNode node) {
        List<ReviewResponseDto.LibraryInfo> libraries = new ArrayList<>();

        if (node == null || !node.isArray()) {
            return libraries;
        }

        for (JsonNode item : node) {
            try {
                // Handle both string format (legacy) and object format (new)
                if (item.isTextual()) {
                    // Legacy format: just a string like "Swashbuckle"
                    libraries.add(ReviewResponseDto.LibraryInfo.builder()
                            .name(item.asText())
                            .version("Unknown")
                            .releaseDate("")
                            .purpose("")
                            .build());
                } else {
                    // New format: object with name, version, releaseDate, purpose
                    libraries.add(ReviewResponseDto.LibraryInfo.builder()
                            .name(getStringValue(item, "name", "Unknown"))
                            .version(getStringValue(item, "version", "Unknown"))
                            .releaseDate(getStringValue(item, "releaseDate", ""))
                            .purpose(getStringValue(item, "purpose", ""))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to parse library: {}", e.getMessage());
            }
        }

        return libraries;
    }

    /**
     * Parse build tool with version
     */
    private ReviewResponseDto.BuildToolInfo parseBuildTool(JsonNode node) {
        if (node == null) {
            return createDefaultBuildTool();
        }

        // Handle both string format (legacy) and object format (new)
        if (node.isTextual()) {
            // Legacy format: just a string like "Maven"
            return ReviewResponseDto.BuildToolInfo.builder()
                    .name(node.asText())
                    .version("Unknown")
                    .releaseDate("")
                    .build();
        } else {
            // New format: object with name, version, releaseDate
            return ReviewResponseDto.BuildToolInfo.builder()
                    .name(getStringValue(node, "name", "Unknown"))
                    .version(getStringValue(node, "version", "Unknown"))
                    .releaseDate(getStringValue(node, "releaseDate", ""))
                    .build();
        }
    }

    /**
     * Parse code quality
     */
    private ReviewResponseDto.CodeQuality parseCodeQuality(JsonNode jsonNode) {
        if (!jsonNode.has("codeQuality")) {
            return createDefaultCodeQuality();
        }

        JsonNode qualityNode = jsonNode.get("codeQuality");

        return ReviewResponseDto.CodeQuality.builder()
                .rating(getStringValue(qualityNode, "rating", "Unknown"))
                .issues(parseStringList(qualityNode.get("issues")))
                .bestPractices(parseStringList(qualityNode.get("bestPractices")))
                .estimatedTestCoverage(getIntValue(qualityNode, "estimatedTestCoverage", 0))
                .build();
    }

    /**
     * Parse string list from JSON array
     */
    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();

        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }

        return result;
    }

    /**
     * Create default build tool
     */
    private ReviewResponseDto.BuildToolInfo createDefaultBuildTool() {
        return ReviewResponseDto.BuildToolInfo.builder()
                .name("Unknown")
                .version("Unknown")
                .releaseDate("")
                .build();
    }

    /**
     * Get string value from JSON node with default
     */
    private String getStringValue(JsonNode node, String field, String defaultValue) {
        try {
            return (node != null && node.has(field)) ? node.get(field).asText() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Get int value from JSON node with default
     */
    private int getIntValue(JsonNode node, String field, int defaultValue) {
        try {
            return (node != null && node.has(field)) ? node.get(field).asInt() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Get double value from JSON node with default
     */
    private Double getDoubleValue(JsonNode node, String field, Double defaultValue) {
        try {
            return (node != null && node.has(field)) ? node.get(field).asDouble() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Get boolean value from JSON node with default
     */
    private Boolean getBooleanValue(JsonNode node, String field, Boolean defaultValue) {
        try {
            return (node != null && node.has(field)) ? node.get(field).asBoolean() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Extract JSON from potential Markdown formatting
     */
    private String extractJson(String text) {
        if (text == null || text.isEmpty()) return "{}";

        // Remove markdown code fences
        text = text.replaceAll("(?s)```json\\s*", "").replaceAll("```\\s*", "").trim();

        int jsonStart = text.indexOf("{");
        if (jsonStart < 0) return "{}";

        text = text.substring(jsonStart);

        // Check if JSON is complete
        int jsonEnd = text.lastIndexOf("}");
        if (jsonEnd > 0) {
            String candidate = text.substring(0, jsonEnd + 1);
            try {
                objectMapper.readTree(candidate); // valid → use it
                return candidate;
            } catch (Exception ignored) {
                // truncated — fall through to repair
            }
        }

        // Repair truncated JSON by closing all open brackets
        log.warn("⚠️ Truncated JSON detected, attempting repair...");
        return repairTruncatedJson(text);
    }
    private String repairTruncatedJson(String truncated) {
        StringBuilder sb = new StringBuilder(truncated);
        Deque<Character> stack = new ArrayDeque<>();

        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);

            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
            }
        }

        // If we're mid-string, close it
        if (inString) sb.append('"');

        // Close all open brackets in reverse order
        while (!stack.isEmpty()) {
            sb.append(stack.pop());
        }

        try {
            objectMapper.readTree(sb.toString());
            log.info("✅ JSON repair successful");
            return sb.toString();
        } catch (Exception e) {
            log.error("❌ JSON repair failed: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Build repository metadata
     */
    private ReviewResponseDto.RepositoryMetadata buildMetadata(GitHubRepository repoData) {
        return ReviewResponseDto.RepositoryMetadata.builder()
                .description(repoData.getDescription())
                .stars(repoData.getStars())
                .forks(repoData.getForks())
                .language(repoData.getLanguage())
                .defaultBranch(repoData.getDefaultBranch())
                .createdAt(repoData.getCreatedAt())
                .updatedAt(repoData.getUpdatedAt())
                .build();
    }

    /**
     * Build error response for empty code repositories
     */
    private ReviewResponseDto buildEmptyCodeResponse(GitHubRepository repoData, long startTime) {
        return ReviewResponseDto.builder()
                .repositoryName(repoData.getFullName())
                .repositoryUrl(repoData.getHtmlUrl())
                .metadata(buildMetadata(repoData))
                .analysis(ReviewResponseDto.CodeAnalysis.builder()
                        .projectStatus("No Code Found")
                        .completionPercentage(0)
                        .summary("This repository does not contain analyzable source code files. It may be documentation, assets, or a placeholder repository.")
                        .strengths(List.of())
                        .improvements(List.of())
                        .suggestedUpdates(List.of())
                        .techStack(createDefaultTechStack())
                        .codeQuality(createDefaultCodeQuality())
                        .build())
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /**
     * Build error response
     */
    private ReviewResponseDto buildErrorResponse(String message, String errorCode, long startTime) {
        return ReviewResponseDto.builder()
                .errorMessage(ReviewResponseDto.ErrorMessage.builder()
                        .message(message)
                        .errorCode(errorCode)
                        .timestamp(new java.util.Date().toString())
                        .build())
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /**
     * Create fallback analysis when parsing fails
     */
    private ReviewResponseDto.CodeAnalysis createFallbackAnalysis(String rawText) {
        return ReviewResponseDto.CodeAnalysis.builder()
                .projectStatus("Unknown")
                .completionPercentage(0)
                .summary(rawText.length() > 300 ? rawText.substring(0, 300) + "..." : rawText)
                .strengths(List.of("Unable to parse detailed strengths"))
                .improvements(List.of())
                .suggestedUpdates(List.of())
                .techStack(createDefaultTechStack())
                .codeQuality(createDefaultCodeQuality())
                .build();
    }

    /**
     * Create default tech stack
     */
    private ReviewResponseDto.TechStackAnalysis createDefaultTechStack() {
        return ReviewResponseDto.TechStackAnalysis.builder()
                .languages(new ArrayList<>())
                .frameworks(new ArrayList<>())
                .libraries(new ArrayList<>())
                .buildTool(createDefaultBuildTool())
                .architecturePatterns(new ArrayList<>())
                .targetPlatform("Unknown")
                .build();
    }

    /**
     * Create default code quality
     */
    private ReviewResponseDto.CodeQuality createDefaultCodeQuality() {
        return ReviewResponseDto.CodeQuality.builder()
                .rating("Unknown")
                .issues(new ArrayList<>())
                .bestPractices(new ArrayList<>())
                .estimatedTestCoverage(0)
                .build();
    }
}