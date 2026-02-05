package com.repolens.repolens_backend.service;

import com.repolens.repolens_backend.model.GitHubContent;
import com.repolens.repolens_backend.model.GitHubRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GitHubService {

    private final WebClient githubWebClient;

    @Value("${github.api.token:}")
    private String githubToken;

    public GitHubService(@Qualifier("githubWebClient") WebClient githubWebClient) {
        this.githubWebClient = githubWebClient;
    }

    /**
     * Extract owner and repo name from GitHub URL
     * Example: https://github.com/spring-projects/spring-boot
     * Returns: ["spring-projects", "spring-boot"]
     */
    public String[] parseGitHubUrl(String repoUrl) {
        Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)");
        Matcher matcher = pattern.matcher(repoUrl);

        if (matcher.find()) {
            String owner = matcher.group(1);
            String repo = matcher.group(2).replace(".git", ""); // Remove .git if present
            log.debug("parsegithub url.....{}/{}",owner,repo);
            return new String[]{owner, repo};
        }

        throw new IllegalArgumentException("Invalid GitHub URL format");
    }

    /**
     * Get repository metadata (stars, forks, language, etc.)
     */
    public GitHubRepository getRepository(String owner, String repo) {
        log.info("Fetching repository metadata: {}/{}", owner, repo);

        WebClient.RequestHeadersSpec<?> request = githubWebClient
                .get()
                .uri("/repos/{owner}/{repo}", owner, repo);

        // Add auth token if available (increases rate limit)
        if (!githubToken.isEmpty()) {
            request = request.header("Authorization", "Bearer " + githubToken);
        }

        try {
            return request
                    .retrieve()
                    .bodyToMono(GitHubRepository.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch repository: {}", e.getMessage());
            throw new RuntimeException("Repository not found or inaccessible: " + owner + "/" + repo);
        }
    }

    /**
     * Get repository contents (list of files/folders)
     */
    public List<GitHubContent> getContents(String owner, String repo, String path) {
        log.debug("Fetching contents: {}/{} at path: {}", owner, repo, path);

        WebClient.RequestHeadersSpec<?> request = githubWebClient
                .get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path);

        if (!githubToken.isEmpty()) {
            request = request.header("Authorization", "Bearer " + githubToken);
        }

        return request
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GitHubContent>>() {})
                .onErrorResume(e -> {
                    log.warn("Error fetching contents for path {}: {}", path, e.getMessage());
                    return Mono.just(List.of());
                })
                .block();
    }

    /**
     * Get file content (decoded from base64)
     */
    public String getFileContent(String owner, String repo, String path) {
        log.debug("Fetching file content: {}/{}/{}", owner, repo, path);

        WebClient.RequestHeadersSpec<?> request = githubWebClient
                .get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path);

        if (!githubToken.isEmpty()) {
            request = request.header("Authorization", "Bearer " + githubToken);
        }

        try {
            GitHubContent content = request
                    .retrieve()
                    .bodyToMono(GitHubContent.class)
                    .block();

            if (content != null && content.getContent() != null) {
                // GitHub returns base64 encoded content with newlines
                String base64Content = content.getContent().replaceAll("\\s", "");
                return new String(Base64.getDecoder().decode(base64Content));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch file content for {}: {}", path, e.getMessage());
        }

        return "";
    }

    /**
     * Collect important files from repository
     * This is the main method that gathers code for AI analysis
     */
    public String collectRepositoryCode(String owner, String repo) {
        StringBuilder codeBuilder = new StringBuilder();
        int maxFiles = 15; // Limit to avoid overwhelming the AI
        int fileCount = 0;

        log.info("Collecting repository code for {}/{}", owner, repo);

        // Get root directory contents
        List<GitHubContent> rootContents = getContents(owner, repo, "");

        // Priority files to check first (most informative)
        String[] priorityFiles = {
                "README.md",
                "package.json",      // Node.js
                "pom.xml",           // Java Maven
                "build.gradle",      // Java Gradle
                "requirements.txt",  // Python
                "setup.py",          // Python
                "Cargo.toml",        // Rust
                "go.mod",            // Go
                "composer.json",     // PHP
                "Gemfile"            // Ruby
        };

        // Collect priority files first
        for (String fileName : priorityFiles) {
            if (fileCount >= maxFiles) break;

            for (GitHubContent item : rootContents) {
                if (item.getName().equalsIgnoreCase(fileName) && "file".equals(item.getType())) {
                    try {
                        String content = getFileContent(owner, repo, item.getPath());
                        if (!content.isEmpty()) {
                            codeBuilder.append("\n\n=== File: ").append(item.getPath()).append(" ===\n");
                            codeBuilder.append(content);
                            fileCount++;
                            log.debug("Collected file: {}", item.getPath());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch priority file {}: {}", fileName, e.getMessage());
                    }
                    break;
                }
            }
        }

        // Collect source code files
        fileCount = collectSourceFiles(owner, repo, rootContents, codeBuilder, fileCount, maxFiles);

        log.info("Collected {} files totaling {} characters", fileCount, codeBuilder.length());

        return codeBuilder.toString();
    }

    /**
     * Recursively collect source code files
     */
    private int collectSourceFiles(String owner, String repo, List<GitHubContent> contents,
                                   StringBuilder codeBuilder, int currentCount, int maxFiles) {
        int count = currentCount;

        for (GitHubContent item : contents) {
            if (count >= maxFiles) break;

            if ("file".equals(item.getType())) {
                String name = item.getName().toLowerCase();

                // Check if it's a source code file we care about
                if (isSourceCodeFile(name)) {
                    try {
                        String content = getFileContent(owner, repo, item.getPath());

                        // Skip very large files (> 10KB)
                        if (!content.isEmpty() && content.length() < 10000) {
                            codeBuilder.append("\n\n=== File: ").append(item.getPath()).append(" ===\n");
                            codeBuilder.append(content);
                            count++;
                            log.debug("Collected source file: {}", item.getPath());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch file {}: {}", item.getName(), e.getMessage());
                    }
                }
            } else if ("dir".equals(item.getType()) && count < maxFiles) {
                // Explore important directories
                String dirName = item.getName().toLowerCase();
                if (isImportantDirectory(dirName)) {
                    List<GitHubContent> subContents = getContents(owner, repo, item.getPath());
                    count = collectSourceFiles(owner, repo, subContents, codeBuilder, count, maxFiles);
                }
            }
        }

        return count;
    }

    /**
     * Check if file is a source code file we want to analyze
     */
    private boolean isSourceCodeFile(String fileName) {
        String[] extensions = {
                ".java", ".js", ".jsx", ".ts", ".tsx",   // Java, JavaScript, TypeScript
                ".py", ".rb", ".php",                     // Python, Ruby, PHP
                ".go", ".rs", ".cpp", ".c", ".cs",       // Go, Rust, C++, C, C#
                ".swift", ".kt", ".scala",                // Swift, Kotlin, Scala
                ".vue", ".html", ".css"                   // Frontend
        };

        for (String ext : extensions) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if directory should be explored
     */
    private boolean isImportantDirectory(String dirName) {
        // Explore these directories
        String[] importantDirs = {"src", "lib", "app", "components", "services", "controllers", "models"};

        for (String dir : importantDirs) {
            if (dirName.equals(dir)) {
                return true;
            }
        }

        // Skip these directories
        String[] skipDirs = {"node_modules", "build", "dist", "target", ".git", "vendor", "venv"};

        for (String dir : skipDirs) {
            if (dirName.equals(dir)) {
                return false;
            }
        }

        return false; // By default, skip unknown directories
    }
}