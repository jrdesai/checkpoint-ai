package io.github.jrdesai.checkpoint_ai.audit;

import java.math.BigDecimal;
import java.time.Instant;

public record AuditRecord(
        String workflowId,
        String repoName,
        int modulesDocumented,
        String modelUsed,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCostUsd,
        String reviewerName,
        Instant approvedAt,
        String outputPath
) {}
