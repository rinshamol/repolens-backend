package com.repolens.repolens_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.repolens.repolens_backend.dto.ReviewRequestDto;
import com.repolens.repolens_backend.dto.ReviewResponseDto;
import com.repolens.repolens_backend.model.GitHubRepository;
import com.repolens.repolens_backend.service.GitHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final GitHubService gitHubService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;
    private final DeepSeekService deepSeekService;
    private final OpenRouterService openRouterService;
    /**
     * Main orchestration method - coordinates the entire review process
     */
    public ReviewResponseDto reviewRepository(ReviewRequestDto request) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting review for: {}", request.getRepoUrl());

            // Step 1: Parse GitHub URL
            String[] ownerRepo = gitHubService.parseGitHubUrl(request.getRepoUrl());
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];

            log.info("Parsed repository: {}/{}", owner, repo);

            // Step 2: Fetch repository metadata from GitHub
            GitHubRepository repoData = gitHubService.getRepository(owner, repo);
            log.info("Fetched metadata: {} stars, {} forks", repoData.getStars(), repoData.getForks());

            // Step 3: Collect repository code
            String repositoryCode = gitHubService.collectRepositoryCode(owner, repo);
            log.info("Collected {} characters of code", repositoryCode.length());



            if (repositoryCode.isEmpty()) {
                log.warn("No code files found in repository {}", repoData.getFullName());
                return ReviewResponseDto.builder()
                        .repositoryName(repoData.getFullName())
                        .repositoryUrl(repoData.getHtmlUrl())
                        .metadata(buildMetadata(repoData))
                        .analysis(ReviewResponseDto.CodeAnalysis.builder()
                                .projectStatus("No Code Found")
                                .summary("This repository does not contain source code files. It may be documentation, assets, or a placeholder.")
                                .build())
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Step 4: Analyze with Gemini AI
//            String analysisJson = geminiService.analyzeRepository(
//                    repositoryCode,
//                    repoData.getName(),
//                    repoData.getDescription()
//            );

//            String analysisJson = deepSeekService.analyzeRepository(
//                    repositoryCode,
//                    repoData.getName(),
//                    repoData.getDescription()
//            );
            String analysisJson = openRouterService.analyzeRepository(
                    repositoryCode,
                    repoData.getName(),
                    repoData.getDescription()
            );

            // Step 5: Parse AI analysis into structured response
            ReviewResponseDto.CodeAnalysis analysis = parseAnalysis(analysisJson);
            if (analysis.getTechStack() != null &&
                    (analysis.getTechStack().getBuildTool() == null ||
                            analysis.getTechStack().getBuildTool().equals("Unknown"))) {

                String buildTool = detectBuildTool(repositoryCode);
                analysis.getTechStack().setBuildTool(buildTool);
                log.info("Auto-detected build tool: {}", buildTool);
            }
            // Step 6: Generate UI mockup if requested
//            String generatedUi = null;
//            if (request.isIncludeUiGeneration()) {
//                log.info("Generating UI mockup...");
//                String techStack = analysis.getTechStack() != null ?
//                        String.join(", ", analysis.getTechStack().getLanguages()) : "Unknown";
//                generatedUi = geminiService.generateUi(repositoryCode, techStack);
//            }

            // Step 7: Build complete response
            ReviewResponseDto.RepositoryMetadata metadata = buildMetadata(repoData);


            long processingTime = System.currentTimeMillis() - startTime;
            log.info("Review completed in {}ms", processingTime);

            return ReviewResponseDto.builder()
                    .repositoryName(repoData.getFullName())
                    .repositoryUrl(repoData.getHtmlUrl())
                    .metadata(metadata)
                    .analysis(analysis)
//                    .generatedUi(generatedUi)
                    .processingTimeMs(processingTime)
                    .build();



        } catch (IllegalArgumentException e) {
            log.error("Invalid input: {}", e.getMessage());
            return ReviewResponseDto.builder()
                    .errorMessage(ReviewResponseDto.ErrorMessage.builder()
                            .message("Invalid input: " + e.getMessage())
                            .build())
                    .build();


        } catch (Exception e) {
            log.error("Error reviewing repository: {}", e.getMessage(), e);
            return ReviewResponseDto.builder()
                    .errorMessage(ReviewResponseDto.ErrorMessage.builder()
                            .message("Error reviewing repository: " + e.getMessage())
                            .build())
                    .build();
        }
    }
    private ReviewResponseDto.RepositoryMetadata buildMetadata(GitHubRepository repoData) {
        return ReviewResponseDto.RepositoryMetadata.builder()
                .description(repoData.getDescription())
                .stars(repoData.getStars())
                .forks(repoData.getForks())
                .language(repoData.getLanguage())
                .defaultBranch(repoData.getDefaultBranch())
                .createdAt(repoData.getCreatedAt())
                .updatedAt(repoData.getUpdatedAt())
                .topics(repoData.getTopics())
                .build();
    }
    private String detectBuildTool(String code) {
        if (code.contains("\"expo\"") || code.contains("expo.json")) {
            return "Expo CLI";
        }
        if (code.contains("yarn.lock")) {
            return "Yarn";
        }
        if (code.contains("package-lock.json") || code.contains("package.json")) {
            return "npm";
        }
        if (code.contains("pnpm-lock.yaml")) {
            return "pnpm";
        }
        if (code.contains("<artifactId>maven")) {
            return "Maven";
        }
        if (code.contains("pom.xml")) {
            return "Maven";
        }
        if (code.contains("build.gradle")) {
            return "Gradle";
        }
        if (code.contains("requirements.txt")) {
            return "pip";
        }
        if (code.contains("Cargo.toml")) {
            return "Cargo";
        }
        if (code.contains("Gemfile")) {
            return "Bundler";
        }
        return "Unknown";
    }
    private ReviewResponseDto buildErrorResponse(String message) {
        ReviewResponseDto.ErrorMessage error = ReviewResponseDto.ErrorMessage.builder()
                .message(message)
                .build();
        return ReviewResponseDto.builder()
                .errorMessage(error)
                .build();
    }

    /**
     * Parse Gemini's JSON response into CodeAnalysis object
     */
    private ReviewResponseDto.CodeAnalysis parseAnalysis(String analysisJson) {
        try {
            // Clean up the response - remove markdown code fences if present
            String cleanJson = extractJson(analysisJson);

            JsonNode jsonNode = objectMapper.readTree(cleanJson);

            // Parse tech stack
            ReviewResponseDto.TechStackAnalysis techStack = parseTechStack(jsonNode);

            // Parse code quality
            ReviewResponseDto.CodeQuality codeQuality = parseCodeQuality(jsonNode);

            // Build CodeAnalysis object
            return ReviewResponseDto.CodeAnalysis.builder()
                    .projectStatus(getStringValue(jsonNode, "projectStatus", "Unknown"))
                    .completionPercentage(getIntValue(jsonNode, "completionPercentage", 0))
                    .summary(getStringValue(jsonNode, "summary", "No summary available"))
                    .strengths(parseStringList(jsonNode.get("strengths")))
                    .improvements(parseStringList(jsonNode.get("improvements")))
                    .suggestedUpdates(parseStringList(jsonNode.get("suggestedUpdates")))
                    .techStack(techStack)
                    .codeQuality(codeQuality)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing analysis JSON: {}", e.getMessage());
            log.debug("Raw JSON: {}", analysisJson);

            // Return a fallback analysis with the raw text
            return createFallbackAnalysis(analysisJson);
        }
    }

    /**
     * Extract JSON from potential Markdown formatting
     */
    private String extractJson(String text) {
        // Remove Markdown code fences if present
        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "");

        // Find JSON object
        int jsonStart = text.indexOf("{");
        int jsonEnd = text.lastIndexOf("}");

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1);
        }

        return text;
    }

    /**
     * Parse tech stack from JSON
     */
    private ReviewResponseDto.TechStackAnalysis parseTechStack(JsonNode jsonNode) {
        if (!jsonNode.has("techStack")) {
            return createDefaultTechStack();
        }

        JsonNode techNode = jsonNode.get("techStack");

        return ReviewResponseDto.TechStackAnalysis.builder()
                .languages(parseStringList(techNode.get("languages")))
                .frameworks(parseStringList(techNode.get("frameworks")))
                .libraries(parseStringList(techNode.get("libraries")))
                .buildTool(getStringValue(techNode, "buildTool", "Unknown"))
                .build();
    }

    /**
     * Parse code quality from JSON
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
                .build();
    }

    /**
     * Parse JSON array to List<String>
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
     * Get string value from JSON node with default
     */
    private String getStringValue(JsonNode node, String field, String defaultValue) {
        return node.has(field) ? node.get(field).asText() : defaultValue;
    }

    /**
     * Get int value from JSON node with default
     */
    private int getIntValue(JsonNode node, String field, int defaultValue) {
        return node.has(field) ? node.get(field).asInt() : defaultValue;
    }

    /**
     * Create fallback analysis when JSON parsing fails
     */
    private ReviewResponseDto.CodeAnalysis createFallbackAnalysis(String rawText) {
        return ReviewResponseDto.CodeAnalysis.builder()
                .projectStatus("Unknown")
                .completionPercentage(0)
                .summary(rawText.length() > 500 ? rawText.substring(0, 500) + "..." : rawText)
                .strengths(List.of("Analysis completed - see summary for details"))
                .improvements(List.of("Unable to parse detailed analysis"))
                .suggestedUpdates(List.of("Please try again or check the summary"))
                .techStack(createDefaultTechStack())
                .codeQuality(createDefaultCodeQuality())
                .build();
    }

    private ReviewResponseDto.TechStackAnalysis createDefaultTechStack() {
        return ReviewResponseDto.TechStackAnalysis.builder()
                .languages(List.of("Unknown"))
                .frameworks(List.of())
                .libraries(List.of())
                .buildTool("Unknown")
                .build();
    }

    private ReviewResponseDto.CodeQuality createDefaultCodeQuality() {
        return ReviewResponseDto.CodeQuality.builder()
                .rating("Unknown")
                .issues(List.of())
                .bestPractices(List.of())
                .build();
    }
}