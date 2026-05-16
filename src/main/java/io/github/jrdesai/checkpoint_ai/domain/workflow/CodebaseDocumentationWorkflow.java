package io.github.jrdesai.checkpoint_ai.domain.workflow;

import io.github.jrdesai.checkpoint_ai.domain.model.ApprovalDecision;
import io.github.jrdesai.checkpoint_ai.domain.model.DocumentationResult;
import io.github.jrdesai.checkpoint_ai.domain.model.WorkflowStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow interface for the codebase documentation generator.
 *
 * Three method types:
 * - @WorkflowMethod  — entry point, called once to start the workflow
 * - @SignalMethod    — called externally to send events into a running workflow
 * - @QueryMethod     — called externally to read state without side effects
 */
@WorkflowInterface
public interface CodebaseDocumentationWorkflow {

    /**
     * Main workflow method — starts the documentation generation process.
     * Temporal calls this once and tracks everything that happens inside it.
     *
     * @param repoUrl the GitHub repository URL to document
     * @return the final result once the workflow completes
     */
    @WorkflowMethod
    DocumentationResult generate(String repoUrl);

    /**
     * Signal method — receives the human approval decision.
     * Called via REST API when a reviewer approves or rejects the draft.
     * Wakes the workflow from its sleep at Step 6.
     *
     * @param decision the reviewer's approval or rejection
     */
    @SignalMethod
    void submitApproval(ApprovalDecision decision);

    /**
     * Query method — returns the current execution status.
     * Safe to call at any time — does not affect workflow execution.
     * Used by the status REST endpoint to show progress.
     *
     * @return current step the workflow is executing
     */
    @QueryMethod
    WorkflowStatus getStatus();

    /**
     * Query method — returns the workflow ID.
     * Useful for correlating REST requests to running workflows.
     *
     * @return the workflow ID assigned by Temporal
     */
    @QueryMethod
    String getWorkflowId();

    /**
     * Query method — returns the repo name.
     * Useful for correlating repo names to running workflows.
     *
     * @return the repo name
     */
    @QueryMethod
    String getRepoName();
}