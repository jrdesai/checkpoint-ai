package io.github.jrdesai.checkpoint_ai.api.dto;

import io.github.jrdesai.checkpoint_ai.domain.model.WorkflowStatus;

public record WorkflowStatusResponse(
        String workflowId,
        String repoName,
        WorkflowStatus status
) {}
