package com.repolens.repolens_backend.controller;

import com.repolens.repolens_backend.dto.AuthorizationRequiredDto;
import com.repolens.repolens_backend.dto.ReviewRequestDto;
import com.repolens.repolens_backend.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:3000", "http://localhost:5173"})
@Tag(name = "Repository Analysis", description = "Analyze GitHub repositories")
public class RepoController {

    private final ReviewService reviewService;

    /**
     * MAIN ENDPOINT: Review a GitHub repository
     * Extracts token manually from the Authorization header to bypass Spring Security session issues.
     */
    @PostMapping("/review")
    @Operation(
            summary = "Analyze Repository",
            description = "Analyze any GitHub repository. Uses the Bearer token from the header for private repos."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Analysis successful"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public ResponseEntity<?> reviewRepository(
            @Valid @RequestBody ReviewRequestDto request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("📊 Repository analysis request: {}", request.getRepoUrl());

        // Extract token from "Bearer <token>"
        String token = extractTokenFromHeader(authHeader);

        try {
            log.info("token {}", token);
            // The service will use the token if provided, or trigger auth if the repo is private
            Object result = reviewService.reviewRepository(request, token);

            if (result instanceof AuthorizationRequiredDto) {
                log.info("🔐 Private repo detected: Authorization required");
                return ResponseEntity.ok().body(result);
            }

            return ResponseEntity.ok(result);

        }  catch (WebClientResponseException.Unauthorized e) {
            // 401: Definitely needs auth
            log.warn("⚠️ Private repo - auth required (401)");
            return ResponseEntity.ok("PRIVATE");
        } catch (WebClientResponseException.Forbidden e) {
            // 403: Could be private or rate limited
            log.warn("⚠️ Access forbidden - likely private (403)");
            return ResponseEntity.ok("PRIVATE");
        } catch (WebClientResponseException.NotFound e) {
            // 404: Repository doesn't exist
            log.warn("⚠️ Repository not found (404)");
            return ResponseEntity.ok("INACCESSIBLE");
        } catch (WebClientResponseException e) {
            // Other errors
            log.warn("⚠️ Unexpected error: {}", e.getStatusCode());
            return ResponseEntity.ok("INACCESSIBLE");
        } catch (Exception e) {
            log.error("❌ Error: {}", e.getMessage());
            return ResponseEntity.ok("INACCESSIBLE");
        }
    }

    /**
     * Get current user info using the Bearer token
     */
    @GetMapping("/user-info")
    @Operation(summary = "Get Current User Info", description = "Returns debug info about the received token")
    public ResponseEntity<?> getUserInfo(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractTokenFromHeader(authHeader);

        if (token == null) {
            return ResponseEntity.status(401).body(createErrorResponse("Not authenticated", "Bearer token missing"));
        }

        // For now, this returns a debug map.
        // In the next step, you can call githubService.getUser(token) here.
        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("authenticated", true);
        debugInfo.put("token_preview", token.substring(0, Math.min(token.length(), 10)) + "...");
        debugInfo.put("note", "Token successfully received by backend");

        return ResponseEntity.ok(debugInfo);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/")
    @Operation(summary = "Health Check")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return ResponseEntity.ok(response);
    }

    /**
     * Simple token extraction logic
     */
    private String extractTokenFromHeader(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * Standard error response builder
     */
    private Map<String, Object> createErrorResponse(String error, String details) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", error);
        response.put("details", details);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}