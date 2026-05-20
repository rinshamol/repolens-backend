# RepoLens — Backend

Spring Boot REST API backend for RepoLens — an AI-powered GitHub repository analyzer. Fetches repository code via the GitHub API, builds a comprehensive analysis prompt, and sends it to OpenRouter AI to generate detailed insights.

🔗 **Live API:** [repolens-backend-ts71.onrender.com](https://repo-lens-frontent.vercel.app/)  
🔗 **Frontend Repo:** [github.com/rinshamol/repo-lens-frontent](https://github.com/rinshamol/repo-lens-frontent)

---

## ✨ Features

- 🔍 Analyze any public GitHub repository
- 🔐 GitHub OAuth2 proxy for private repository access
- 🤖 AI-powered analysis via OpenRouter API using `arcee-ai/trinity-large-thinking:free`
- 📊 Comprehensive analysis output including:
  - Project status and completion percentage
  - Code quality rating and best practices
  - Tech stack detection with versions and release dates
  - Risk assessment and security vulnerabilities
  - Actionable improvement suggestions with effort/time estimates
  - Suggested package updates with breaking change warnings
- ⚡ Caching to avoid redundant API calls
- 🔄 Retry logic for resilient API communication
- 🛡️ Spring Security with CORS configuration

---

## 🛠️ Built With

| Technology | Purpose |
|---|---|
| Spring Boot 4.0 | REST API framework |
| Java 21 | Runtime |
| Spring WebFlux (WebClient) | Reactive HTTP client |
| Spring Security + OAuth2 | GitHub authentication |
| Spring Cache | Response caching |
| Spring Retry | Resilient API calls |
| Lombok | Boilerplate reduction |
| Jackson | JSON parsing |
| Springdoc OpenAPI | API documentation |
| Docker | Containerization |
| Render | Cloud deployment |

---

## 📁 Project Structure

```
repolens-backend/
├── src/main/java/com/repolens/repolens_backend/
│   ├── config/
│   │   ├── WebClientConfig.java     # WebClient beans for GitHub, OpenRouter, Auth
│   │   ├── SecurityConfig.java      # Spring Security and CORS configuration
│   │   ├── CorsConfig.java          # CORS settings
│   │   └── SwaggerConfig.java       # OpenAPI documentation
│   ├── controller/
│   │   ├── RepoController.java      # Main review endpoint
│   │   └── AuthProxyController.java # GitHub OAuth token exchange proxy
│   ├── service/
│   │   ├── ReviewService.java       # Orchestrates the full analysis flow
│   │   ├── GitHubService.java       # GitHub API integration
│   │   └── OpenRouterService.java   # OpenRouter AI API integration
│   ├── dto/                         # Request/Response DTOs
│   └── model/                       # Domain models
├── Dockerfile
├── pom.xml
└── src/main/resources/
    └── application.properties
```

---

## ⚙️ Getting Started

### Prerequisites
- Java 21
- Maven 3.9+
- GitHub OAuth App (Client ID + Secret)
- OpenRouter API key (free tier available)
- GitHub Personal Access Token (optional, for higher API rate limits)

### Installation

```bash
# Clone the repository
git clone https://github.com/rinshamol/repolens-backend.git
cd repolens-backend

# Install dependencies
./mvnw clean install -DskipTests
```

### Environment Variables

Set these in your IDE run configuration or terminal:

```env
OPENROUTER_API_KEY=your_openrouter_api_key
GITHUB_CLIENT_ID=your_github_oauth_client_id
GITHUB_CLIENT_SECRET=your_github_oauth_client_secret
GITHUB_TOKEN=your_github_personal_access_token
FRONTEND_URL=http://localhost:5173
```

### Run Locally

```bash
./mvnw spring-boot:run
```

API available at [http://localhost:8080](http://localhost:8080)

---

## 📡 API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/review` | Analyze a GitHub repository |
| `POST` | `/api/auth/token` | Exchange GitHub OAuth code for token |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/swagger-ui.html` | API documentation |

### Example Request

```json
POST /api/review
{
  "repoUrl": "https://github.com/username/repository"
}
```

---

## 🐳 Docker

```bash
# Build image
docker build -t repolens-backend .

# Run container
docker run -p 8080:8080 \
  -e OPENROUTER_API_KEY=your_key \
  -e GITHUB_CLIENT_ID=your_id \
  -e GITHUB_CLIENT_SECRET=your_secret \
  repolens-backend
```

---

## 🌐 Deployment

Deployed on **Render** using Docker with automatic CI/CD — every push to `master` triggers a rebuild and redeploy.

### Render Environment Variables

| Key | Description |
|---|---|
| `OPENROUTER_API_KEY` | OpenRouter API key |
| `GITHUB_CLIENT_ID` | GitHub OAuth App Client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App Client Secret |
| `GITHUB_TOKEN` | GitHub Personal Access Token |
| `FRONTEND_URL` | Deployed frontend URL |

---

## 🔐 GitHub OAuth Flow

```
Frontend → GitHub OAuth authorize
  → GitHub redirects to backend /login/oauth2/code/github
    → Backend exchanges code for access token via /api/auth/token
      → Access token returned to frontend
        → Frontend uses token for private repo access
```

---

## 🎯 What I Learned

- Building a production-ready REST API with Spring Boot 4
- Integrating multiple external APIs (GitHub API + OpenRouter AI)
- Implementing GitHub OAuth2 proxy for secure token exchange
- Managing named WebClient beans with @Qualifier in Spring
- Containerizing a Spring Boot app with Docker multi-stage builds
- Deploying a Java backend on Render with environment variables
- Handling AI response parsing with JSON repair for truncated responses
- Implementing retry logic and caching for resilient API communication

---

> Made with 💙 by [Rinshamol](https://github.com/rinshamol)
