package io.github.jrdesai.checkpoint_ai.domain.workflow;

import io.github.jrdesai.checkpoint_ai.activity.CodebaseDocumentationActivities;
import io.github.jrdesai.checkpoint_ai.domain.model.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Temporal workflow implementation for codebase documentation generation.
 * All real work happens in activities.
 * This class only orchestrates the sequence.
 */
@Slf4j
@WorkflowImpl(taskQueues = "codebase-doc-generator")
public class CodebaseDocumentationWorkflowImpl
        implements CodebaseDocumentationWorkflow {

    private WorkflowStatus currentStatus = WorkflowStatus.STARTED;
    private String workflowId;
    private String repoName;

    private ApprovalDecision approvalDecision = null;
    private DocumentDraft currentDraft = null;

    // Activity stub
    // LLM calls (steps 3, 4, 5) get longer timeouts
    private final CodebaseDocumentationActivities activities =
            Workflow.newActivityStub(
                    CodebaseDocumentationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(10))
                            // Temporal retries immediately if no heartbeat within this window
                            // rather than waiting for the full startToCloseTimeout
                            .setHeartbeatTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(10))
                                    .setBackoffCoefficient(2.0)
                                    // Don't retry on bad input — only on infrastructure failure
                                    .setDoNotRetry(
                                            IllegalArgumentException.class.getName(),
                                            IllegalStateException.class.getName()
                                    )
                                    .build())
                            .build()
            );

    //  Fast activities stub — for non-LLM steps
    private final CodebaseDocumentationActivities fastActivities =
            Workflow.newActivityStub(
                    CodebaseDocumentationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(5)
                                    .setInitialInterval(Duration.ofSeconds(5))
                                    .build())
                            .build()
            );

    @Override
    public DocumentationResult generate(String repoUrl) {

        // Capture workflow ID — safe to call inside workflow code
        workflowId = Workflow.getInfo().getWorkflowId();
        Workflow.getLogger(this.getClass())
                .info("Starting documentation workflow for: {}", repoUrl);

        // ── STEP 1 — Clone and inventory ─────────────────────────────
        // Fast, no LLM, use fastActivities stub
        currentStatus = WorkflowStatus.CLONING_REPOSITORY;
        RepositoryInfo repoInfo = fastActivities.cloneAndInventory(repoUrl);
        repoName = repoInfo.repoName();

        // ── STEP 2 — Static analysis per module ──────────────────────
        // Pure computation — no LLM, fast per module
        currentStatus = WorkflowStatus.ANALYSING_COMPLEXITY;
        List<ComplexityReport> complexityReports = new ArrayList<>();

        for (ModuleInfo module : repoInfo.modules()) {
            // Each call is independently checkpointed
            // If we crash after module 5 of 20, we resume at module 6
            ComplexityReport report = fastActivities.analyseComplexity(module);
            complexityReports.add(report);
        }

        // ── STEP 2b — Load cached narratives ─────────────────────────
        Map<String, ModuleNarrative> cache = fastActivities.loadCachedNarratives(
                repoInfo.repoName(), repoInfo.modules()
        );

        // ── STEP 3 — LLM explanation per module ──────────────────────
        // One LLM call per module
        // Each call checkpointed independently — never repeated on crash
        currentStatus = WorkflowStatus.EXPLAINING_MODULES;
        List<ModuleNarrative> narratives = new ArrayList<>();

        for (int i = 0; i < repoInfo.modules().size(); i++) {
            ModuleInfo module = repoInfo.modules().get(i);
            ComplexityReport complexity = complexityReports.get(i);

            if (cache.containsKey(module.filePath())) {
                // Unchanged — use cached narrative, no LLM call
                narratives.add(cache.get(module.filePath()));
                Workflow.getLogger(this.getClass())
                        .info("Cache hit {}/{}: {}", i + 1,
                                repoInfo.modules().size(), module.name());
            } else {
                // New or changed — call LLM
                ModuleNarrative narrative = activities.explainModule(module, complexity);
                narratives.add(narrative);
                Workflow.getLogger(this.getClass())
                        .info("Explained module {}/{}: {}", i + 1,
                                repoInfo.modules().size(), module.name());
            }
        }

        // ── STEP 4 — Architectural analysis ──────────────────────────
        // One LLM call across all modules
        currentStatus = WorkflowStatus.ANALYSING_ARCHITECTURE;
        ArchitecturalAnalysis architecture = activities.analyseArchitecture(
                narratives, complexityReports
        );

        // ── STEP 5 — Assemble document ────────────────────────────────
        // One LLM call — produces the full markdown document
        currentStatus = WorkflowStatus.ASSEMBLING_DOCUMENT;
        currentDraft = activities.assembleDocument(
                narratives, architecture, repoInfo
        );
        DocumentDraft draft = currentDraft;

        // ── STEP 6 — Human approval ───────────────────────────────────
        // Notify reviewer then sleep until signal received
        currentStatus = WorkflowStatus.AWAITING_APPROVAL;
        fastActivities.notifyReviewerReady(draft, workflowId);

        // Workflow sleeps here — no thread held, no resources wasted
        // Server can restart many times — workflow stays waiting
        // Times out after 48 hours if no decision received
        boolean signalReceived = Workflow.await(
                Duration.ofHours(48),
                () -> approvalDecision != null
        );

        // ── STEP 6b — Handle timeout ──────────────────────────────────
        if (!signalReceived || approvalDecision == null) {
            currentStatus = WorkflowStatus.FAILED;
            Workflow.getLogger(this.getClass())
                    .warn("Workflow timed out waiting for approval: {}",
                            workflowId);
            return new DocumentationResult(
                    workflowId,
                    repoInfo.repoName(),
                    null,
                    false,
                    narratives.size(),
                    draft.estimatedCostUsd(),
                    Workflow.currentTimeMillis() > 0
                            ? java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis())
                            : null,
                    WorkflowStatus.FAILED
            );
        }

        // ── STEP 6c — Handle rejection ────────────────────────────────
        if (!approvalDecision.approved()) {
            currentStatus = WorkflowStatus.REJECTED;
            fastActivities.handleRejection(draft, approvalDecision.comments());
            return new DocumentationResult(
                    workflowId,
                    repoInfo.repoName(),
                    null,
                    false,
                    narratives.size(),
                    draft.estimatedCostUsd(),
                    java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                    WorkflowStatus.REJECTED
            );
        }

        // ── STEP 7 — Publish approved document ───────────────────────
        // Idempotent — safe to retry if crash occurs here
        currentStatus = WorkflowStatus.PUBLISHING;
        DocumentationResult result = fastActivities.publishDocument(
                draft, workflowId, approvalDecision
        );

        // ── STEP 8 — Save module cache ────────────────────────────────
        fastActivities.saveModuleCache(
                repoInfo.repoName(), repoInfo.modules(), narratives
        );

        currentStatus = WorkflowStatus.COMPLETED;
        Workflow.getLogger(this.getClass())
                .info("Workflow completed successfully: {}", workflowId);

        return result;
    }

    // ── Signal handler — called externally via REST API ───────────────
    // Wakes the workflow from its sleep at step 6
    @Override
    public void submitApproval(ApprovalDecision decision) {
        this.approvalDecision = decision;
        Workflow.getLogger(this.getClass())
                .info("Approval received: approved={} reviewer={}",
                        decision.approved(), decision.reviewerName());
    }

    // Query handlers
    @Override
    public WorkflowStatus getStatus() {
        return currentStatus;
    }

    @Override
    public String getWorkflowId() {
        return workflowId;
    }

    @Override
    public String getRepoName() {
        return repoName;
    }

    @Override
    public DocumentDraft getDraft() {
        return currentDraft;
    }

}