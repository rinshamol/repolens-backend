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


    private Map<String, Object> createErrorResponse(String error, String details) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", error);
        response.put("details", details);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}