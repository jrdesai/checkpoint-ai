package io.github.jrdesai.checkpoint_ai.domain.model;

import java.time.Instant;

public record RevisionRequest(
        String reviewerName,
        String feedback,
        Instant requestedAt
) {}
