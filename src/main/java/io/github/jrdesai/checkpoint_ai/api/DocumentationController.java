package io.github.jrdesai.checkpoint_ai.api;

import io.github.jrdesai.checkpoint_ai.api.dto.ApprovalRequest;
import io.github.jrdesai.checkpoint_ai.api.dto.GenerateRequest;
import io.github.jrdesai.checkpoint_ai.api.dto.RevisionRequestDto;
import io.github.jrdesai.checkpoint_ai.api.dto.WorkflowStatusResponse;
import io.github.jrdesai.checkpoint_ai.domain.model.DocumentDraft;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentationController {

    private final DocumentationService documentationService;

    @PostMapping(value = "/generate")
    public ResponseEntity<String> generate(@Valid @RequestBody GenerateRequest request){
        String workflowId = documentationService.startWorkflow( request.repoUrl());

        return ResponseEntity.status(HttpStatus.CREATED).body(workflowId);
    }

    @PostMapping(value = "/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable String id, @Valid @RequestBody ApprovalRequest request){
        documentationService.submitApproval(id, request, true);

        return ResponseEntity.ok().build();
    }

    @PostMapping(value="/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id, @Valid @RequestBody ApprovalRequest request){
        documentationService.submitApproval(id, request, false);

        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/{id}/revise")
    public ResponseEntity<Void> revise(@PathVariable String id, @Valid @RequestBody RevisionRequestDto request) {
        documentationService.requestRevision(id, request);

        return ResponseEntity.ok().build();
    }

    @GetMapping(value ="/{id}/status")
    public ResponseEntity<WorkflowStatusResponse> status(@PathVariable String id) {
        WorkflowStatusResponse response = documentationService.getStatus(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{id}/draft")
    public ResponseEntity<DocumentDraft> draft(@PathVariable String id) {
        return ResponseEntity.ok(documentationService.getDraft(id));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<List<String>> handleValidationErrors(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());

        return ResponseEntity.badRequest().body(errors);
    }
}
