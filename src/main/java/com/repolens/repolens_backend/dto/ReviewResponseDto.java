package com.repolens.repolens_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponseDto {

    private String repositoryName;
    private String repositoryUrl;
    private RepositoryMetadata metadata;
    private CodeAnalysis analysis;
    private String generatedUi; // HTML/React code for UI preview
    private long processingTimeMs;
    private ErrorMessage errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepositoryMetadata {
        private String description;
        private int stars;
        private int forks;
        private String language;
        private String defaultBranch;
        private String createdAt;
        private String updatedAt;
        private List<String> topics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeAnalysis {
        private String projectStatus; // "Complete", "In Progress", "Incomplete"
        private int completionPercentage;
        private String summary;
        private List<String> strengths;
        private List<String> improvements;
        private List<String> suggestedUpdates;
        private TechStackAnalysis techStack;
        private CodeQuality codeQuality;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechStackAnalysis {
        private List<String> languages;
        private List<String> frameworks;
        private List<String> libraries;
        private String buildTool;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeQuality {
        private String rating; // "Excellent", "Good", "Fair", "Needs Improvement"
        private List<String> issues;
        private List<String> bestPractices;
    }
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public  static  class ErrorMessage {
        private String message;
    }
}