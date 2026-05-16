package io.github.jrdesai.checkpoint_ai.domain.model;

/**
 * Current execution status of a documentation workflow.
 * Returned by the @QueryMethod so callers can check progress.
 */
public enum WorkflowStatus {
    STARTED,
    CLONING_REPOSITORY,
    ANALYSING_COMPLEXITY,
    EXPLAINING_MODULES,
    ANALYSING_ARCHITECTURE,
    ASSEMBLING_DOCUMENT,
    AWAITING_APPROVAL,
    PUBLISHING,
    COMPLETED,
    REJECTED,
    FAILED
}