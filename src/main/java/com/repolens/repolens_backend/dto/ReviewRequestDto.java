package com.repolens.repolens_backend.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ReviewRequestDto {
    @NotBlank(message = "GitHub URL is required")
    @Pattern(
            regexp = "^https://github\\.com/[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+/?$",
            message = "Invalid GitHub repository URL format"
    )
    private String repoUrl;

    private boolean includeUiGeneration = false;

    private String focusAreas;
}
