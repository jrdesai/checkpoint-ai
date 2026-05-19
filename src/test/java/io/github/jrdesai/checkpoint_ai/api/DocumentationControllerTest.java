package io.github.jrdesai.checkpoint_ai.api;

import io.github.jrdesai.checkpoint_ai.api.dto.WorkflowStatusResponse;
import io.github.jrdesai.checkpoint_ai.domain.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class DocumentationControllerTest {

    @Mock
    private DocumentationService documentationService;

    @InjectMocks
    private DocumentationController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();
    }

    @Test
    void generateReturns201WithWorkflowId() throws Exception {
        when(documentationService.startWorkflow(any())).thenReturn("doc-my-repo");

        mockMvc.perform(post("/api/docs/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repoUrl": "https://github.com/owner/my-repo.git"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().string("doc-my-repo"));
    }

    @Test
    void generateReturnsBadRequestWhenRepoUrlIsBlank() throws Exception {
        mockMvc.perform(post("/api/docs/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repoUrl": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateReturnsBadRequestWhenRepoUrlIsMissing() throws Exception {
        mockMvc.perform(post("/api/docs/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveReturns200() throws Exception {
        mockMvc.perform(post("/api/docs/doc-my-repo/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName": "Jigar", "comments": "LGTM"}
                                """))
                .andExpect(status().isOk());

        verify(documentationService).submitApproval(eq("doc-my-repo"), any(), eq(true));
    }

    @Test
    void rejectReturns200() throws Exception {
        mockMvc.perform(post("/api/docs/doc-my-repo/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName": "Jigar", "comments": "Needs more detail"}
                                """))
                .andExpect(status().isOk());

        verify(documentationService).submitApproval(eq("doc-my-repo"), any(), eq(false));
    }

    @Test
    void statusReturns200WithWorkflowStatus() throws Exception {
        when(documentationService.getStatus("doc-my-repo"))
                .thenReturn(new WorkflowStatusResponse(
                        "doc-my-repo", "my-repo", WorkflowStatus.AWAITING_APPROVAL
                ));

        mockMvc.perform(get("/api/docs/doc-my-repo/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("doc-my-repo"))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
    }

    @Test
    void statusReturns404WhenWorkflowNotFound() throws Exception {
        when(documentationService.getStatus("doc-unknown"))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workflow not found"
                ));

        mockMvc.perform(get("/api/docs/doc-unknown/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approveReturnsBadRequestWhenReviewerNameIsBlank() throws Exception {
        mockMvc.perform(post("/api/docs/doc-my-repo/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerName": "", "comments": "LGTM"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
