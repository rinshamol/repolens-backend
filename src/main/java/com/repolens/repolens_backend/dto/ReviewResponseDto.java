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
    private String generatedUi;
    private long processingTimeMs;
    private ErrorMessage errorMessage;

    // ============================================================================
    // METADATA
    // ============================================================================

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
    }

    // ============================================================================
    // CODE ANALYSIS - MAIN SECTION
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeAnalysis {
        private String projectStatus;
        private int completionPercentage;
        private String summary;
        private List<String> strengths;
        private List<ImprovementItem> improvements;
        private List<UpdateItem> suggestedUpdates;
        private TechStackAnalysis techStack;
        private CodeQuality codeQuality;
        private RiskAssessment riskAssessment;
    }

    // ============================================================================
    // IMPROVEMENT ITEM
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImprovementItem {
        private String id;
        private String description;
        private String effortLevel;        // Easy, Medium, Hard
        private Double estimatedHours;     // e.g., 3.5
        private Integer estimatedDays;     // e.g., 1
        private String estimatedTime;      // "2-3 hours", "1-2 days" - for backward compatibility
        private String category;           // Security, Testing, Performance, etc.
        private String impact;             // Business impact
        private String priority;           // Critical, High, Medium, Low
        private List<String> tags;         // Keywords for filtering
    }

    // ============================================================================
    // UPDATE ITEM
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateItem {
        private String id;
        private String packageName;
        private String currentVersion;
        private String recommendedVersion;
        private String latestVersion;
        private String releaseDate;
        private String changelog;
        private String priority;
        private Boolean breakingChanges;
        private Double estimatedHours;
        private String reason;
        private String impact;
        private String riskLevel;
        private Boolean tested;
    }

    // ============================================================================
    // TECH STACK - ENHANCED WITH VERSIONS
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechStackAnalysis {
        private List<LanguageInfo> languages;      // NEW: With versions
        private List<FrameworkInfo> frameworks;    // NEW: With versions
        private List<LibraryInfo> libraries;       // NEW: With versions
        private BuildToolInfo buildTool;           // NEW: With version
        private List<String> architecturePatterns; // NEW: Design patterns used
        private String targetPlatform;             // NEW: .NET/Java/Node etc
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LanguageInfo {
        private String name;               // e.g., "C#", "Java", "Python"
        private String version;            // e.g., "12.0", "11", "3.11"
        private String releaseDate;        // e.g., "2023-11-15"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrameworkInfo {
        private String name;               // e.g., "ASP.NET Core", "Spring Boot"
        private String version;            // e.g., "8.0", "3.2.0"
        private String releaseDate;        // e.g., "2023-11-14"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LibraryInfo {
        private String name;               // e.g., "Swashbuckle.AspNetCore"
        private String version;            // e.g., "6.5.0"
        private String releaseDate;        // e.g., "2023-12-01"
        private String purpose;            // e.g., "API Documentation", "ORM"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildToolInfo {
        private String name;               // e.g., "dotnet CLI", "Maven", "npm"
        private String version;            // e.g., "8.0", "3.9.1", "10.2.0"
        private String releaseDate;        // e.g., "2023-11-08"
    }

    // ============================================================================
    // CODE QUALITY
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeQuality {
        private String rating;
        private List<String> issues;
        private List<String> bestPractices;
        private int estimatedTestCoverage;
    }

    // ============================================================================
    // RISK ASSESSMENT
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private String overallRiskLevel;
        private List<Risk> risks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Risk {
        private String issue;
        private String severity;
        private String mitigation;
    }

    // ============================================================================
    // ERROR MESSAGE
    // ============================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorMessage {
        private String message;
        private String errorCode;
        private String timestamp;
    }
}