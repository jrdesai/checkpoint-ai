package io.github.jrdesai.checkpoint_ai.domain.model;

import java.time.Instant;

/**
 * Human approval decision sent via Temporal signal.
 * Wakes the workflow from its sleep at Step 6.
 */
public record ApprovalDecision(
        String workflowId,
        boolean approved,
        String reviewerName,
        String comments,
        Instant decidedAt
) {}