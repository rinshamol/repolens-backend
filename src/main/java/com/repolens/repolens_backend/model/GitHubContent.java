package com.repolens.repolens_backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GitHubContent {

    private String name;
    private String path;
    private String type; // "file" or "dir"

    @JsonProperty("download_url")
    private String downloadUrl;

    private String content; // Base64 encoded for files
    private String encoding;
    private long size;
}