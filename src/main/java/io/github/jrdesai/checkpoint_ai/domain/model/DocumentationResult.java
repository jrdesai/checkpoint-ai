package io.github.jrdesai.checkpoint_ai.domain.model;

import java.time.Instant;

/**
 * Final result of the documentation workflow.
 * Returned when the workflow completes successfully.
 */
public record DocumentationResult(
        String workflowId,
        String repoName,
        String outputPath,
        boolean approved,
        int modulesDocumented,
        double totalCostUsd,
        Instant completedAt,
        WorkflowStatus status
) {}