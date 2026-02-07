package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.repolens_backend.dto.ReviewRequestDto;
import com.repolens.repolens_backend.dto.ReviewResponseDto;
import com.repolens.repolens_backend.model.GitHubRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final GitHubService gitHubService;
    private final OpenRouterService openRouterService;
    private final ObjectMapper objectMapper;

    /**
     * Main orchestration method - coordinates the entire review process
     */
    public ReviewResponseDto reviewRepository(ReviewRequestDto request) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("🔍 Starting repository review: {}", request.getRepoUrl());

            // Step 1: Parse and validate GitHub URL
            String[] ownerRepo = gitHubService.parseGitHubUrl(request.getRepoUrl());
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            log.debug("✓ Parsed repository: {}/{}", owner, repo);

            // Step 2: Fetch repository metadata from GitHub
            GitHubRepository repoData = gitHubService.getRepository(owner, repo);
            log.info("✓ Repository found: {} stars, {} forks", repoData.getStars(), repoData.getForks());

            // Step 3: Collect repository code
            String repositoryCode = gitHubService.collectRepositoryCode(owner, repo);

            if (repositoryCode.isEmpty()) {
                log.warn("⚠️ No code files found in repository {}", repoData.getFullName());
                return buildEmptyCodeResponse(repoData, startTime);
            }

            log.info("✓ Collected {} characters of code", repositoryCode.length());

            // Step 4: Analyze with OpenRouter AI
            String analysisJson = openRouterService.analyzeRepository(
                    repositoryCode,
                    repoData.getName(),
                    repoData.getDescription()
            );

            // Step 5: Parse AI analysis into structured response
            ReviewResponseDto.CodeAnalysis analysis = parseAnalysis(analysisJson);

            // Step 6: Build complete response
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
            return buildErrorResponse(
                    "Invalid GitHub URL: " + e.getMessage(),
                    "INVALID_INPUT",
                    startTime
            );
        } catch (RuntimeException e) {
            log.error("❌ Repository error: {}", e.getMessage());
            return buildErrorResponse(
                    "Repository not found or inaccessible: " + e.getMessage(),
                    "REPOSITORY_NOT_FOUND",
                    startTime
            );
        } catch (Exception e) {
            log.error("❌ Unexpected error: {}", e.getMessage(), e);
            return buildErrorResponse(
                    "Failed to review repository: " + e.getMessage(),
                    "INTERNAL_ERROR",
                    startTime
            );
        }
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
                        .estimatedTime(getStringValue(item, "estimatedTime", "N/A"))
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
        // Remove Markdown code fences
        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "");

        // Find JSON object boundaries
        int jsonStart = text.indexOf("{");
        int jsonEnd = text.lastIndexOf("}");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1);
        }

        return text;
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