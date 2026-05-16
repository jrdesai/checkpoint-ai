package io.github.jrdesai.checkpoint_ai.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * The assembled architecture document awaiting human approval.
 * Output of Step 5.
 */
public record DocumentDraft(
        String workflowId,
        String repoName,
        String markdownContent,
        List<String> modulesCovered,
        int totalInputTokens,
        int totalOutputTokens,
        double estimatedCostUsd,
        Instant generatedAt
) {}