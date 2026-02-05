package com.repolens.repolens_backend.controller;

import com.repolens.repolens_backend.dto.ReviewRequestDto;
import com.repolens.repolens_backend.dto.ReviewResponseDto;
import com.repolens.repolens_backend.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RepoController {
    private final ReviewService reviewService;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "GitHub Reviewer API");
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return ResponseEntity.ok(response);
    }

    /**
     * Review a GitHub repository
     */
    @PostMapping("/review")
    public ResponseEntity<?> reviewRepository(@Valid @RequestBody ReviewRequestDto request) {
        log.info("Received review request for: {}", request.getRepoUrl());

        try {
            ReviewResponseDto response = reviewService.reviewRepository(request);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Invalid GitHub URL format", e.getMessage()));

        } catch (Exception e) {
            log.error("Error processing review: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to review repository", e.getMessage()));
        }
    }

    /**
     * Get sample response (for testing without API calls)
     */
    @GetMapping("/sample")
    public ResponseEntity<ReviewResponseDto> getSample() {
        ReviewResponseDto sample = ReviewResponseDto.builder()
                .repositoryName("sample/demo-repo")
                .repositoryUrl("https://github.com/sample/demo-repo")
                .metadata(ReviewResponseDto.RepositoryMetadata.builder()
                        .description("A sample Spring Boot application")
                        .stars(150)
                        .forks(30)
                        .language("Java")
                        .defaultBranch("main")
                        .createdAt("2023-01-15T10:00:00Z")
                        .updatedAt("2024-02-01T15:30:00Z")
                        .topics(java.util.List.of("spring-boot", "java", "rest-api"))
                        .build())
                .analysis(ReviewResponseDto.CodeAnalysis.builder()
                        .projectStatus("Complete")
                        .completionPercentage(85)
                        .summary("This is a well-structured Spring Boot REST API with clean architecture and good documentation.")
                        .strengths(java.util.List.of(
                                "Clean code structure following best practices",
                                "Comprehensive documentation",
                                "Proper error handling and validation",
                                "Good use of design patterns"
                        ))
                        .improvements(java.util.List.of(
                                "Add more unit tests to improve coverage",
                                "Implement caching for better performance",
                                "Add API rate limiting"
                        ))
                        .suggestedUpdates(java.util.List.of(
                                "Update to latest Spring Boot version",
                                "Add API documentation with Swagger/OpenAPI",
                                "Implement logging with structured format"
                        ))
                        .techStack(ReviewResponseDto.TechStackAnalysis.builder()
                                .languages(java.util.List.of("Java"))
                                .frameworks(java.util.List.of("Spring Boot", "Spring Data JPA"))
                                .libraries(java.util.List.of("Lombok", "Jackson"))
                                .buildTool("Maven")
                                .build())
                        .codeQuality(ReviewResponseDto.CodeQuality.builder()
                                .rating("Good")
                                .issues(java.util.List.of("Some minor code duplication in service layer"))
                                .bestPractices(java.util.List.of(
                                        "Uses dependency injection",
                                        "Follows RESTful conventions",
                                        "Proper exception handling"
                                ))
                                .build())
                        .build())
                .processingTimeMs(2500L)
                .build();

        return ResponseEntity.ok(sample);
    }

    private Map<String, Object> createErrorResponse(String error, String details) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", error);
        response.put("details", details);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}

