package io.github.jrdesai.checkpoint_ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalRequest(
        @NotBlank(message = "reviewerName is required") String reviewerName,
        String comments
) {}
