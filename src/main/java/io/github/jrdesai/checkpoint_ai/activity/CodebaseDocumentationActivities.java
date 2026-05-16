package io.github.jrdesai.checkpoint_ai.activity;

import io.github.jrdesai.checkpoint_ai.domain.model.*;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Temporal activities interface for the codebase documentation workflow.
 *
 * Each @ActivityMethod is:
 * - Independently checkpointed by Temporal
 * - Never re-executed after successful completion
 * - Automatically retried on failure
 * - Independently configurable for timeout and retry behaviour
 *
 * Implementation lives in CodebaseDocumentationActivitiesImpl.
 */
@ActivityInterface
public interface CodebaseDocumentationActivities {

    /**
     * Step 1 — Clone the repository and inventory all Java modules.
     * No LLM involved. Fast, cheap, deterministic.
     *
     * @param repoUrl the GitHub repository URL
     * @return repository metadata and list of discovered modules
     */
    @ActivityMethod
    RepositoryInfo cloneAndInventory(String repoUrl);

    /**
     * Step 2 — Run static analysis on a single module.
     * Uses JavaParser and PMD. No LLM. Pure computation.
     * Called once per module — Temporal checkpoints each call.
     *
     * @param module the module to analyse
     * @return precise complexity metrics — never estimated by LLM
     */
    @ActivityMethod
    ComplexityReport analyseComplexity(ModuleInfo module);

    /**
     * Step 3 — Ask the LLM to explain a single module.
     * Expensive — one LLM call per module.
     * Grounded by ComplexityReport metrics passed as context.
     * Checkpointed — never repeated after successful completion.
     *
     * @param module    the module to explain
     * @param complexity precise metrics from step 2
     * @return LLM-generated narrative grounded in real metrics
     */
    @ActivityMethod
    ModuleNarrative explainModule(ModuleInfo module,
                                  ComplexityReport complexity);

    /**
     * Step 4 — Analyse architectural patterns across all modules.
     * One LLM call for the entire codebase.
     * Detects coupling, anti-patterns, architectural style.
     *
     * @param narratives all module narratives from step 3
     * @param complexityReports all complexity reports from step 2
     * @return architectural analysis with patterns and migration path
     */
    @ActivityMethod
    ArchitecturalAnalysis analyseArchitecture(
            List<ModuleNarrative> narratives,
            List<ComplexityReport> complexityReports);

    /**
     * Step 5 — Assemble all narratives into a coherent document.
     * One LLM call — produces the final markdown document.
     *
     * @param narratives         all module narratives from step 3
     * @param analysis           architectural analysis from step 4
     * @param repoInfo           repository metadata from step 1
     * @return assembled document draft awaiting human approval
     */
    @ActivityMethod
    DocumentDraft assembleDocument(List<ModuleNarrative> narratives,
                                   ArchitecturalAnalysis analysis,
                                   RepositoryInfo repoInfo);

    /**
     * Step 6a — Notify the engineer that a document is ready for review.
     * Called before the workflow sleeps waiting for approval signal.
     * In production: sends email or Slack notification.
     * For demo: logs to console.
     *
     * @param draft the document draft ready for review
     * @param workflowId the Temporal workflow ID for the approval signal
     */
    @ActivityMethod
    void notifyReviewerReady(DocumentDraft draft, String workflowId);

    /**
     * Step 7 — Publish the approved document.
     * Writes the final markdown to the output folder.
     * Idempotent — safe to retry, will not duplicate output.
     *
     * @param draft    the approved document draft
     * @param workflowId the workflow ID used for output filename
     * @return final documentation result with output path and metrics
     */
    @ActivityMethod
    DocumentationResult publishDocument(DocumentDraft draft,
                                        String workflowId,
                                        ApprovalDecision approvalDecision);

    /**
     * Rejection handler — called if the reviewer rejects the document.
     * Logs the rejection reason and cleans up any temporary files.
     *
     * @param draft    the rejected document draft
     * @param reason   the reviewer's rejection reason
     */
    @ActivityMethod
    void handleRejection(DocumentDraft draft, String reason);
}