package io.github.jrdesai.checkpoint_ai.api;

import io.github.jrdesai.checkpoint_ai.api.dto.ApprovalRequest;
import io.github.jrdesai.checkpoint_ai.api.dto.WorkflowStatusResponse;
import io.github.jrdesai.checkpoint_ai.domain.model.ApprovalDecision;
import io.github.jrdesai.checkpoint_ai.domain.model.WorkflowStatus;
import io.github.jrdesai.checkpoint_ai.domain.workflow.CodebaseDocumentationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentationService {

    private final WorkflowClient workflowClient;


    public String startWorkflow(String repoUrl) {
        String workflowId = "doc-"+ UUID.randomUUID();
        WorkflowOptions workflowOptions = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue("codebase-doc-generator")
                .setWorkflowExecutionTimeout(Duration.ofHours(2))
                .build();

        CodebaseDocumentationWorkflow stub = workflowClient.newWorkflowStub(
                CodebaseDocumentationWorkflow.class, workflowOptions
        );

        WorkflowClient.start(stub::generate, repoUrl);

        return workflowId;
    }

    public void submitApproval(String workflowId, ApprovalRequest request, boolean approved){
        try {
            CodebaseDocumentationWorkflow stub = workflowClient.newWorkflowStub(
                    CodebaseDocumentationWorkflow.class, workflowId
            );

            ApprovalDecision approvalDecision = new ApprovalDecision(
                    workflowId,
                    approved,
                    request.reviewerName(),
                    request.comments(),
                    Instant.now()
            );

            stub.submitApproval(approvalDecision);

        } catch (WorkflowNotFoundException e){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Workflow not found: " + workflowId);
        }
    }

    public WorkflowStatusResponse getStatus(String workflowId){
        try {
            CodebaseDocumentationWorkflow stub = workflowClient.newWorkflowStub(
                    CodebaseDocumentationWorkflow.class, workflowId
            );
            WorkflowStatus status = stub.getStatus();

            return new WorkflowStatusResponse(workflowId, stub.getRepoName(), status);
        } catch (WorkflowNotFoundException  e){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Workflow not found: " + workflowId);
        }

    }

}
