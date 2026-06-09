package io.github.jrdesai.checkpoint_ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RevisionRequestDto(
        @NotBlank(message = "reviewerName is required") String reviewerName,
        @NotBlank(message = "feedback is required") String feedback
) {}
