package io.github.jrdesai.checkpoint_ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateRequest(@NotBlank(message = "repoUrl is required") String repoUrl) {
}
