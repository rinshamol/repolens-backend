package com.repolens.repolens_backend.service;

import com.repolens.repolens_backend.model.GitHubContent;
import com.repolens.repolens_backend.model.GitHubRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitHubService {

    private final WebClient githubWebClient;
    private final AtomicInteger rateLimitRemaining = new AtomicInteger(-1);
    private final AtomicInteger rateLimitReset = new AtomicInteger(-1);

    @Value("${github.api.token:}")
    private String githubToken;

    @Value("${github.api.max-files:20}")
    private int maxFiles;

    @Value("${github.api.max-file-size:15000}")
    private int maxFileSize;

    public GitHubService(@Qualifier("githubWebClient") WebClient githubWebClient) {
        this.githubWebClient = githubWebClient;
    }

    /**
     * Parse GitHub URL and extract owner and repo name
     * Example: https://github.com/spring-projects/spring-boot → ["spring-projects", "spring-boot"]
     */
    public String[] parseGitHubUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            throw new IllegalArgumentException("Repository URL cannot be empty");
        }

        Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(repoUrl);

        if (matcher.find()) {
            String owner = matcher.group(1);
            String repo = matcher.group(2);
            log.debug("✓ Parsed GitHub URL: {}/{}", owner, repo);
            return new String[]{owner, repo};
        }

        throw new IllegalArgumentException("Invalid GitHub URL format. Expected: https://github.com/owner/repo");
    }

    /**
     * Fetch repository metadata (stars, forks, language, etc.)
     * Cached for 1 hour to reduce API calls
     */
    @Cacheable(value = "repositories", key = "#owner + '/' + #repo", unless = "#result == null")
    public GitHubRepository getRepository(String owner, String repo) {
        log.info("📦 Fetching repository metadata: {}/{}", owner, repo);

        try {
            WebClient.RequestHeadersSpec<?> request = githubWebClient
                    .get()
                    .uri("/repos/{owner}/{repo}", owner, repo);
            request = request.header("Accept", "application/vnd.github.mercy-preview+json");
            if (isTokenValid()) {
                request = request.header("Authorization", "Bearer " + githubToken);
            }

            GitHubRepository repository = request
                    .retrieve()
                    .bodyToMono(GitHubRepository.class)
                    .timeout(Duration.ofSeconds(15))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)))
                    .block();

            if (repository == null) {
                throw new RuntimeException("Repository response was null");
            }
            if (repository.getTopics() == null) {
                repository.setTopics(new ArrayList<>());
            }

            updateRateLimitStatus();
            log.info("✓ Repository metadata fetched successfully");
            log.info("✓ Found {} topics: {}", repository.getTopics().size(), repository.getTopics());
            return repository;

        } catch (WebClientResponseException.NotFound e) {
            log.error("❌ Repository not found: {}/{}", owner, repo);
            throw new RuntimeException("Repository not found: " + owner + "/" + repo, e);
        } catch (WebClientResponseException e) {
            log.error("❌ GitHub API error ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch repository: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("❌ Unexpected error fetching repository: {}", e.getMessage(), e);
            throw new RuntimeException("Error fetching repository metadata", e);
        }
    }

    /**
     * Get contents of a path (files and directories)
     * Includes retry logic with exponential backoff
     */
    public List<GitHubContent> getContents(String owner, String repo, String path) {
        log.debug("📂 Fetching contents: {}/{} at path: {}", owner, repo, path);

        try {
            WebClient.RequestHeadersSpec<?> request = githubWebClient
                    .get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path);

            if (isTokenValid()) {
                request = request.header("Authorization", "Bearer " + githubToken);
            }

            List<GitHubContent> contents = request
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<GitHubContent>>() {})
                    .timeout(Duration.ofSeconds(15))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .doBeforeRetry(signal -> log.warn("Retrying file listing, attempt: {}", signal.totalRetries() + 1))
                    )
                    .onErrorResume(e -> {
                        log.warn("⚠️ Error fetching contents for path {}: {}", path, e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();

            return contents != null ? contents : List.of();

        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch contents: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get file content with base64 decoding
     * Respects rate limiting and file size limits
     */
    public String getFileContent(String owner, String repo, String path) {
        log.debug("📄 Fetching file: {}/{}/{}", owner, repo, path);

        try {
            if (!hasRateLimitAvailable()) {
                log.warn("⚠️ Rate limit approaching, skipping file fetch");
                return "";
            }

            WebClient.RequestHeadersSpec<?> request = githubWebClient
                    .get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path);

            if (isTokenValid()) {
                request = request.header("Authorization", "Bearer " + githubToken);
            }

            GitHubContent content = request
                    .retrieve()
                    .bodyToMono(GitHubContent.class)
                    .timeout(Duration.ofSeconds(10))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                    .block();

            if (content == null || content.getContent() == null) {
                log.debug("⚠️ Empty content for file: {}", path);
                return "";
            }

            // Decode base64 content
            String decodedContent = decodeBase64Content(content.getContent());

            if (decodedContent.length() > maxFileSize) {
                log.debug("⚠️ File {} exceeds size limit ({} chars), truncating", path, decodedContent.length());
                return decodedContent.substring(0, maxFileSize);
            }

            updateRateLimitStatus();
            return decodedContent;

        } catch (WebClientResponseException e) {
            log.debug("⚠️ Failed to fetch file {}: {}", path, e.getStatusCode());
            return "";
        } catch (Exception e) {
            log.debug("⚠️ Error fetching file {}: {}", path, e.getMessage());
            return "";
        }
    }

    /**
     * Detect language - checks root and subdirectories
     */
    private String detectLanguageWithSubdirectories(String owner, String repo,
                                                    List<GitHubContent> rootContents) {
        log.info("🔍 Detecting language from repository");

        // Check root files first
        for (GitHubContent item : rootContents) {
            if ("file".equals(item.getType())) {
                String name = item.getName().toLowerCase();

                if (name.endsWith(".sln") || name.endsWith(".csproj")) {
                    log.info("✅ C# detected from root files");
                    return "C#";
                }
                if (name.equals("pom.xml") || name.equals("build.gradle")) {
                    log.info("✅ Java detected");
                    return "Java";
                }
                if (name.equals("package.json")) {
                    log.info("✅ JavaScript detected");
                    return "JavaScript";
                }
                if (name.equals("requirements.txt") || name.equals("setup.py")) {
                    log.info("✅ Python detected");
                    return "Python";
                }
            }
        }

        // Check subdirectories for project files
        log.info("📂 Checking subdirectories...");

        for (GitHubContent folder : rootContents) {
            if (!"dir".equals(folder.getType())) {
                continue;
            }

            String folderName = folder.getName().toLowerCase();

            if (shouldSkipCSharpFolder(folderName)) {
                continue;
            }

            try {
                List<GitHubContent> folderContents = getContents(owner, repo, folder.getName());

                for (GitHubContent file : folderContents) {
                    String fileName = file.getName().toLowerCase();

                    if ("file".equals(file.getType()) && fileName.endsWith(".csproj")) {
                        log.info("✅ C# detected - .csproj in {}", folder.getName());
                        return "C#";
                    }
                }
            } catch (Exception e) {
                log.debug("Could not scan {}: {}", folderName, e.getMessage());
            }
        }

        log.warn("⚠️ Language not detected");
        return "Unknown";
    }

    public String collectRepositoryCode(String owner, String repo) {
        log.info("🔍 Collecting code for {}/{}", owner, repo);

        StringBuilder codeBuilder = new StringBuilder();
        List<GitHubContent> rootContents = getContents(owner, repo, "");

        if (rootContents.isEmpty()) {
            log.warn("⚠️ No contents found");
            return "";
        }

        int fileCount = 0;
        fileCount = collectPriorityFiles(owner, repo, rootContents, codeBuilder, fileCount);

        String language = detectLanguageWithSubdirectories(owner, repo, rootContents);
        log.info("📊 Language: {}", language);

        if ("C#".equalsIgnoreCase(language)) {
            log.info("🔷 C# detected - scanning all project folders");
            fileCount = scanAllCSharpFolders(owner, repo, rootContents, codeBuilder, fileCount);
        } else {
            fileCount = collectSourceFiles(owner, repo, rootContents, codeBuilder, fileCount);
        }

        log.info("✓ Collected {} files", fileCount);
        return codeBuilder.toString();
    }

    private int scanAllCSharpFolders(String owner, String repo, List<GitHubContent> rootContents,
                                     StringBuilder codeBuilder, int count) {
        for (GitHubContent folder : rootContents) {
            if (count >= maxFiles) break;

            if (!"dir".equals(folder.getType())) {
                continue;
            }

            String folderName = folder.getName().toLowerCase();

            if (shouldSkipCSharpFolder(folderName)) {
                continue;
            }

            log.debug("📂 Scanning: {}", folder.getName());
            count = scanCSharpProjectRecursive(owner, repo, folder.getName(),
                    codeBuilder, count, 0);
        }

        return count;
    }
    /**
     * Recursively scan C# project for .cs files
     * Depth limit prevents infinite recursion
     */
    private int scanCSharpProjectRecursive(String owner, String repo, String path,
                                           StringBuilder codeBuilder, int count, int depth) {
        // Limit recursion depth
        if (depth > 5 || count >= maxFiles) {
            return count;
        }

        try {
            List<GitHubContent> contents = getContents(owner, repo, path);

            for (GitHubContent item : contents) {
                if (count >= maxFiles) break;

                String itemName = item.getName();
                String itemNameLower = itemName.toLowerCase();

                // Skip build/cache folders
                if (shouldSkipCSharpFolder(itemNameLower)) {
                    log.debug("  ⊘ Skipping: {}", itemName);
                    continue;
                }

                if ("dir".equals(item.getType())) {
                    // Recurse into subdirectory
                    log.debug("  📂 Scanning folder: {}", itemName);
                    count = scanCSharpProjectRecursive(owner, repo, item.getPath(),
                            codeBuilder, count, depth + 1);
                } else if ("file".equals(item.getType())) {
                    // Collect relevant C# files
                    if (isCSharpSourceFile(itemNameLower)) {
                        String content = getFileContent(owner, repo, item.getPath());
                        if (!content.isEmpty()) {
                            appendFileToBuilder(codeBuilder, item.getPath(), content);
                            count++;
                            log.debug("  ✅ Found C# file: {}", item.getPath());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error scanning C# project folder {}: {}", path, e.getMessage());
        }

        return count;
    }

    /**
     * Check if folder should be skipped during C# scanning
     */
    private boolean shouldSkipCSharpFolder(String folderNameLower) {
        return folderNameLower.equals("bin") ||
                folderNameLower.equals("obj") ||
                folderNameLower.equals(".vs") ||
                folderNameLower.equals(".git") ||
                folderNameLower.equals("node_modules") ||
                folderNameLower.equals("packages") ||
                folderNameLower.equals(".claude") ||
                folderNameLower.equals("release") ||
                folderNameLower.equals("debug") ||
                folderNameLower.equals("publish");
    }

    /**
     * Check if file is relevant C# source or config file
     */
    private boolean isCSharpSourceFile(String fileNameLower) {
        return fileNameLower.endsWith(".cs") ||              // C# source
                fileNameLower.endsWith(".csproj") ||          // Project file
                fileNameLower.endsWith(".sln") ||             // Solution file
                fileNameLower.equals("appsettings.json") ||   // Config
                fileNameLower.equals("appsettings.development.json") ||
                fileNameLower.equals("appsettings.production.json") ||
                fileNameLower.equals("program.cs") ||         // Entry point
                fileNameLower.equals("startup.cs") ||         // Startup config
                fileNameLower.endsWith(".xaml");              // XAML files
    }

    /**
     * Collect priority configuration files
     */
    private int collectPriorityFiles(String owner, String repo, List<GitHubContent> rootContents,
                                     StringBuilder codeBuilder, int currentCount) {
        String[] priorityFiles = {
                "README.md",
                "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                "requirements.txt", "setup.py", "Cargo.toml", "go.mod", "composer.json", "Gemfile",
                "docker-compose.yml", "Dockerfile",
                ".github/workflows/main.yml", ".github/workflows/ci.yml",
                "tsconfig.json", "webpack.config.js", "vite.config.ts",
                "src/main.java", "src/main.rs", "src/main.py"
        };

        int count = currentCount;

        for (String fileName : priorityFiles) {
            if (count >= maxFiles) break;

            String content = fetchFileIfExists(owner, repo, rootContents, fileName);
            if (!content.isEmpty()) {
                appendFileToBuilder(codeBuilder, fileName, content);
                count++;
                log.debug("✓ Collected priority file: {}", fileName);
            }
        }

        return count;
    }

    /**
     * Recursively collect source code files - NOW INCLUDES ALL FOLDERS
     * Handles Java projects with various folder structures
     */
    private int collectSourceFiles(String owner, String repo, List<GitHubContent> contents,
                                   StringBuilder codeBuilder, int currentCount) {
        int count = currentCount;

        List<GitHubContent> dirs = new ArrayList<>();
        List<GitHubContent> files = new ArrayList<>();

        for (GitHubContent item : contents) {
            if ("dir".equals(item.getType())) {
                dirs.add(item);
            } else if ("file".equals(item.getType())) {
                files.add(item);
            }
        }

        // Priority 1: Important directories (src, lib, app, etc.)
        String[] priorityDirs = {
                "src", "lib", "app", "components",
                "services", "controllers", "models", "utils", "helpers"
        };

        for (String dirName : priorityDirs) {
            if (count >= maxFiles) break;

            for (GitHubContent dir : dirs) {
                if (dir.getName().equalsIgnoreCase(dirName)) {
                    log.debug("📂 Scanning priority folder: {}", dirName);
                    List<GitHubContent> subContents = getContents(owner, repo, dir.getPath());
                    count = processDirectoryContentsRecursive(owner, repo, subContents, codeBuilder, count, 0);
                    break;
                }
            }
        }

        // Priority 2: Scan ALL remaining folders (important for various project structures!)
        log.debug("📂 Scanning all other folders for source files...");
        for (GitHubContent dir : dirs) {
            if (count >= maxFiles) break;

            String dirName = dir.getName().toLowerCase();

            // Skip build/cache folders
            if (shouldSkipSourceFolder(dirName)) {
                log.debug("  ⊘ Skipping: {}", dirName);
                continue;
            }

            // Skip if already processed
            if (isAlreadyProcessed(dirName, priorityDirs)) {
                continue;
            }

            log.debug("  📂 Scanning folder: {}", dirName);
            List<GitHubContent> subContents = getContents(owner, repo, dir.getPath());
            count = processDirectoryContentsRecursive(owner, repo, subContents, codeBuilder, count, 0);
        }

        // Priority 3: Process root-level source files
        log.debug("📄 Scanning root-level source files...");
        for (GitHubContent file : files) {
            if (count >= maxFiles) break;

            if (isSourceCodeFile(file.getName())) {
                String content = getFileContent(owner, repo, file.getPath());
                if (!content.isEmpty()) {
                    appendFileToBuilder(codeBuilder, file.getPath(), content);
                    count++;
                    log.debug("  ✅ Found source file: {}", file.getName());
                }
            }
        }

        return count;
    }

    /**
     * Process directory contents RECURSIVELY - goes deep into folder structure
     */
    private int processDirectoryContentsRecursive(String owner, String repo,
                                                  List<GitHubContent> contents,
                                                  StringBuilder codeBuilder, int currentCount, int depth) {
        if (depth > 5 || currentCount >= maxFiles) {  // Limit recursion depth
            return currentCount;
        }

        int count = currentCount;

        for (GitHubContent item : contents) {
            if (count >= maxFiles) break;

            String itemName = item.getName().toLowerCase();

            // Skip build/cache folders
            if (shouldSkipSourceFolder(itemName)) {
                continue;
            }

            if ("dir".equals(item.getType())) {
                // Recurse into subdirectories
                log.debug("  📁 Recursing into: {}", item.getName());
                List<GitHubContent> subContents = getContents(owner, repo, item.getPath());
                count = processDirectoryContentsRecursive(owner, repo, subContents, codeBuilder, count, depth + 1);
            } else if ("file".equals(item.getType())) {
                // Collect source files
                if (isSourceCodeFile(item.getName())) {
                    String content = getFileContent(owner, repo, item.getPath());
                    if (!content.isEmpty()) {
                        appendFileToBuilder(codeBuilder, item.getPath(), content);
                        count++;
                        log.debug("    ✅ Found source file: {}", item.getPath());
                    }
                }
            }
        }

        return count;
    }

    /**
     * Check if folder should be skipped (build/cache folders)
     */
    private boolean shouldSkipSourceFolder(String folderNameLower) {
        return folderNameLower.equals("bin") ||
                folderNameLower.equals("obj") ||
                folderNameLower.equals("target") ||      // ← Java build output
                folderNameLower.equals("build") ||       // ← Gradle build
                folderNameLower.equals("dist") ||
                folderNameLower.equals("node_modules") ||
                folderNameLower.equals(".git") ||
                folderNameLower.equals(".vs") ||
                folderNameLower.equals("vendor") ||
                folderNameLower.equals("packages") ||
                folderNameLower.equals(".gradle") ||
                folderNameLower.equals(".m2") ||
                folderNameLower.equals("__pycache__") ||
                folderNameLower.startsWith(".") ||
                folderNameLower.equals("coverage");
    }

    /**
     * Check if folder was already processed
     */
    private boolean isAlreadyProcessed(String folderName, String[] processedFolders) {
        for (String processed : processedFolders) {
            if (folderName.equalsIgnoreCase(processed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Process directory contents recursively
     */
    private int processDirectoryContents(String owner, String repo, List<GitHubContent> contents,
                                         StringBuilder codeBuilder, int currentCount) {
        int count = currentCount;

        for (GitHubContent item : contents) {
            if (count >= maxFiles) break;

            if ("file".equals(item.getType()) && isSourceCodeFile(item.getName())) {
                String content = getFileContent(owner, repo, item.getPath());
                if (!content.isEmpty()) {
                    appendFileToBuilder(codeBuilder, item.getPath(), content);
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Helper: Append file content to builder with markers
     */
    private void appendFileToBuilder(StringBuilder builder, String fileName, String content) {
        builder.append("\n\n=== File: ").append(fileName).append(" ===\n");
        builder.append(content);
    }

    /**
     * Helper: Fetch file if it exists in the root contents
     */
    private String fetchFileIfExists(String owner, String repo, List<GitHubContent> rootContents, String fileName) {
        for (GitHubContent item : rootContents) {
            if (item.getName().equalsIgnoreCase(fileName) && "file".equals(item.getType())) {
                return getFileContent(owner, repo, item.getPath());
            }
        }
        return "";
    }

    /**
     * Check if file is a source code file
     */
    private boolean isSourceCodeFile(String fileName) {
        String lowerName = fileName.toLowerCase();

        String[] extensions = {
                ".java", ".js", ".jsx", ".ts", ".tsx",
                ".py", ".rb", ".php",
                ".go", ".rs", ".cpp", ".c", ".cs", ".h",
                ".swift", ".kt", ".scala",
                ".vue", ".html", ".css", ".scss",
                ".json", ".yaml", ".yml", ".xml", ".toml"
        };

        for (String ext : extensions) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }

        // Include specific config files
        return lowerName.matches("(dockerfile|makefile|.env.*|.editorconfig|.gitignore)");
    }

    /**
     * Decode base64 content from GitHub API
     */
    private String decodeBase64Content(String base64Content) {
        try {
            // GitHub returns base64 with newlines - remove them
            String cleanBase64 = base64Content.replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(cleanBase64);
            return new String(decoded, "UTF-8");
        } catch (Exception e) {
            log.warn("Failed to decode base64 content: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Check if API token is valid
     */
    private boolean isTokenValid() {
        return githubToken != null && !githubToken.isEmpty() && !githubToken.equals("your-token-here");
    }

    /**
     * Check if rate limit is available
     */
    private boolean hasRateLimitAvailable() {
        if (rateLimitRemaining.get() < 0) {
            return true; // Unknown, assume available
        }
        return rateLimitRemaining.get() > 10; // Keep buffer of 10 requests
    }

    /**
     * Update rate limit information from response headers
     * (This should be called after each API request)
     */
    private void updateRateLimitStatus() {
        // This would typically extract from response headers:
        // X-RateLimit-Remaining, X-RateLimit-Reset
        // Implementation depends on WebClient configuration
    }

    /**
     * Get current rate limit status
     */
    public Map<String, Integer> getRateLimitStatus() {
        return Map.of(
                "remaining", rateLimitRemaining.get(),
                "reset", rateLimitReset.get()
        );
    }

    /**
     * Clear cache (useful for manual refresh)
     */
    @CacheEvict(value = "repositories", allEntries = true)
    public void clearCache() {
        log.info("🗑️ Repository cache cleared");
    }
}