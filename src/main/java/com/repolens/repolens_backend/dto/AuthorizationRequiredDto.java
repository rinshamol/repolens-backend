package com.repolens.repolens_backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response for private repository requiring authorization")
public class AuthorizationRequiredDto {

    @Schema(description = "Type of response", example = "AUTHORIZATION_REQUIRED")
    private String type;

    @Schema(description = "User-friendly message", example = "This is a private repository. Redirecting to GitHub authorization...")
    private String message;

    @Schema(description = "Repository URL that was attempted", example = "https://github.com/user/private-repo")
    private String repositoryUrl;

    @Schema(description = "GitHub OAuth authorization URL - frontend should redirect here", example = "https://github.com/login/oauth/authorize?...")
    private String authorizationUrl;

    @Schema(description = "Where to redirect after successful GitHub authorization", example = "http://localhost:3000/analyze?repo=...")
    private String redirectAfterAuth;

    @Schema(description = "Auto-redirect delay in milliseconds", example = "2000")
    private int autoRedirectDelayMs;

    @Schema(description = "Should frontend auto-redirect to authorizationUrl?", example = "true")
    private boolean autoRedirect;
}