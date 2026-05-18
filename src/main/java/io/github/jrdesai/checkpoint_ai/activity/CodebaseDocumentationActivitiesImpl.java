package io.github.jrdesai.checkpoint_ai.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.github.jrdesai.checkpoint_ai.audit.AuditRecord;
import io.github.jrdesai.checkpoint_ai.domain.model.*;
import io.github.jrdesai.checkpoint_ai.persistence.ProcessedModule;
import io.github.jrdesai.checkpoint_ai.persistence.ProcessedModuleRepository;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Implementation of all workflow activities.
 * - File I/O (cloning, reading, writing)
 * - Static analysis (JavaParser)
 * - LLM calls (Spring AI ChatClient)
 * - Notifications (logging for demo)
 * Each method is independently checkpointed by Temporal.
 * If any method completes successfully it will never be re-executed
 * even if the server crashes immediately after.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = "codebase-doc-generator")
public class CodebaseDocumentationActivitiesImpl
        implements CodebaseDocumentationActivities {

    private final ChatClient chatClient;

    @Value("${spring.ai.google.genai.chat.options.model}")
    private String modelName;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Output directory for generated documents
    private static final String OUTPUT_DIR = "output/documentation";

    private final ProcessedModuleRepository processedModuleRepository;

    // ── STEP 1 ────────────────────────────────────────────────────────

    @Override
    public RepositoryInfo cloneAndInventory(String repoUrl) {
        log.info("Cloning and inventorying repository: {}", repoUrl);

        // Clone repository to a temp directory (or use local path directly if provided)
        String repoName = extractRepoName(repoUrl);
        String localPath = cloneRepository(repoUrl, repoName);

        List<ModuleInfo> modules = new ArrayList<>();
        AtomicInteger totalLines = new AtomicInteger(0);

        try {
            Path repoPath = Path.of(localPath);

            if (!Files.exists(repoPath)) {
                throw new IllegalArgumentException(
                        "Repository path does not exist: " + localPath
                );
            }

            // Walk the directory tree and find all Java files
            Files.walkFileTree(repoPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs) throws IOException {

                    if (file.toString().endsWith(".java")
                            && !isTestFile(file)) {

                        String sourceCode = Files.readString(file);
                        List<String> imports = extractImports(sourceCode);
                        int lineCount = sourceCode.split("\n").length;

                        totalLines.addAndGet(lineCount);

                        modules.add(new ModuleInfo(
                                extractClassName(file),
                                extractPackageName(sourceCode),
                                sourceCode,
                                file.toString(),
                                lineCount,
                                imports
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

        } catch (IOException e) {
            log.error("Failed to inventory repository: {}", repoUrl, e);
            throw new RuntimeException(
                    "Failed to inventory repository: " + e.getMessage(), e
            );
        }

        log.info("Inventoried {} Java files in {}", modules.size(), repoName);

        return new RepositoryInfo(
                repoUrl,
                repoName,
                localPath,
                modules,
                modules.size(),
                totalLines.get()
        );
    }

    // ── STEP 2 ────────────────────────────────────────────────────────

    @Override
    public ComplexityReport analyseComplexity(ModuleInfo module) {
        log.info("Analysing complexity for module: {}", module.name());

        JavaParser parser = new JavaParser();
        Map<String, Integer> methodComplexity = new HashMap<>();
        AtomicInteger totalComplexity = new AtomicInteger(1);

        try {
            var result = parser.parse(module.sourceCode());
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();

                // Visit each method and calculate cyclomatic complexity
                cu.accept(new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(MethodDeclaration method, Void arg) {
                        super.visit(method, arg);
                        int complexity = calculateMethodComplexity(method);
                        methodComplexity.put(
                                method.getNameAsString(), complexity
                        );
                        totalComplexity.addAndGet(complexity - 1);
                    }
                }, null);
            }
        } catch (Exception e) {
            log.warn("Could not parse module {}: {}", module.name(),
                    e.getMessage());
        }

        // Find the most complex method
        String mostComplexMethod = methodComplexity.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("none");

        int maxComplexity = methodComplexity.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);

        int cyclomaticComplexity = totalComplexity.get();
        RiskLevel riskLevel = RiskLevel.fromCyclomaticComplexity(
                cyclomaticComplexity
        );

        return new ComplexityReport(
                module.name(),
                cyclomaticComplexity,
                cyclomaticComplexity,        // cognitive ≈ cyclomatic for now
                module.lineCount(),
                methodComplexity.size(),
                mostComplexMethod,
                maxComplexity,
                methodComplexity,
                module.imports(),
                module.imports().size(),
                riskLevel
        );
    }

    // ── STEP 3 ────────────────────────────────────────────────────────

    @Override
    public ModuleNarrative explainModule(ModuleInfo module,
                                         ComplexityReport complexity) {
        log.info("Explaining module: {} (complexity: {}, risk: {})",
                module.name(), complexity.cyclomaticComplexity(),
                complexity.riskLevel());

        // Heartbeat before the LLM call — tells Temporal this activity is alive.
        // If the worker dies here, Temporal retries within heartbeatTimeout (30s)
        // rather than waiting for the full startToCloseTimeout (10 min).
        Activity.getExecutionContext().heartbeat(module.name());

        String prompt = buildExplainModulePrompt(module, complexity);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // Parse the LLM response into structured fields
        return parseModuleNarrative(module.name(), response);
    }

    // ── STEP 4 ────────────────────────────────────────────────────────

    @Override
    public ArchitecturalAnalysis analyseArchitecture(
            List<ModuleNarrative> narratives,
            List<ComplexityReport> complexityReports) {

        log.info("Analysing architecture across {} modules",
                narratives.size());

        Activity.getExecutionContext().heartbeat(
                "Analysing architecture across " + narratives.size() + " modules"
        );

        String prompt = buildArchitecturePrompt(narratives, complexityReports);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseArchitecturalAnalysis(response);
    }

    // ── STEP 5 ────────────────────────────────────────────────────────

    @Override
    public DocumentDraft assembleDocument(
            List<ModuleNarrative> narratives,
            ArchitecturalAnalysis analysis,
            RepositoryInfo repoInfo) {

        log.info("Assembling documentation for: {}", repoInfo.repoName());

        Activity.getExecutionContext().heartbeat(
                "Assembling document for: " + repoInfo.repoName()
        );

        String prompt = buildAssembleDocumentPrompt(
                narratives, analysis, repoInfo
        );

        ChatResponse response = chatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse();

        if (response == null) {
            throw new RuntimeException("LLM returned null response");
        }
        String markdownContent = Optional.ofNullable(response.getResult())
                .map(Generation::getOutput)
                .map(AbstractMessage::getText)
                .orElseThrow(() -> new RuntimeException("LLM returned empty response"));
        int inputTokens = Objects.requireNonNullElse(
                response.getMetadata().getUsage().getPromptTokens(), 0);
        int outputTokens = Objects.requireNonNullElse(
                response.getMetadata().getUsage().getCompletionTokens(), 0);

        // Calculate cost based on actual token usage
        double estimatedCost = (inputTokens * 0.000000125)
                + (outputTokens * 0.000000375);

        List<String> modulesCovered = narratives.stream()
                .map(ModuleNarrative::moduleName)
                .collect(Collectors.toList());

        return new DocumentDraft(
                Activity.getExecutionContext().getInfo().getWorkflowId(),
                repoInfo.repoName(),
                markdownContent,
                modulesCovered,
                inputTokens,
                outputTokens,
                estimatedCost,
                Instant.now()
        );
    }

    // ── STEP 6a ───────────────────────────────────────────────────────

    @Override
    public void notifyReviewerReady(DocumentDraft draft, String workflowId) {
        log.info("""
            ╔══════════════════════════════════════════════════╗
            ║         DOCUMENT READY FOR REVIEW                ║
            ╠══════════════════════════════════════════════════╣
            ║  Repository:  {}
            ║  Workflow ID: {}
            ║  Modules:     {}
            ║  Est. Cost:   ${} USD
            ╠══════════════════════════════════════════════════╣
            ║  To APPROVE:                                     ║
            ║  POST /api/docs/{}/approve                       ║
            ║  To REJECT:                                      ║
            ║  POST /api/docs/{}/reject                        ║
            ╚══════════════════════════════════════════════════╝
            """,
                draft.repoName(),
                workflowId,
                draft.modulesCovered().size(),
                String.format("%.4f", draft.estimatedCostUsd()),
                workflowId,
                workflowId
        );
    }

    // ── STEP 7 ────────────────────────────────────────────────────────

    @Override
    public DocumentationResult publishDocument(DocumentDraft draft,
                                               String workflowId, ApprovalDecision approvalDecision) {
        log.info("Publishing document for: {}", draft.repoName());

        try {
            // Create a dedicated folder per run — workflowId ensures uniqueness
            Path runDir = Path.of(OUTPUT_DIR).resolve(workflowId);
            Files.createDirectories(runDir);

            // Write markdown
            Path outputPath = runDir.resolve("architecture.md");
            Files.writeString(outputPath, draft.markdownContent());

            // Write audit.json alongside the markdown
            AuditRecord auditRecord = new AuditRecord(
                    workflowId,
                    draft.repoName(),
                    draft.modulesCovered().size(),
                    modelName,
                    draft.totalInputTokens(),
                    draft.totalOutputTokens(),
                    BigDecimal.valueOf(draft.estimatedCostUsd())
                            .setScale(6, RoundingMode.HALF_UP),
                    approvalDecision.reviewerName(),
                    approvalDecision.decidedAt(),
                    outputPath.toString()
            );

            Path auditPath = runDir.resolve("audit.json");
            objectMapper.writeValue(auditPath.toFile(), auditRecord);


            log.info("Document published to: {}", outputPath);

            return new DocumentationResult(
                    workflowId,
                    draft.repoName(),
                    outputPath.toString(),
                    true,
                    draft.modulesCovered().size(),
                    draft.estimatedCostUsd(),
                    Instant.now(),
                    WorkflowStatus.COMPLETED
            );

        } catch (IOException e) {
            log.error("Failed to publish document: {}", e.getMessage(), e);
            throw new RuntimeException(
                    "Failed to publish document: " + e.getMessage(), e
            );
        }
    }

    // ── REJECTION HANDLER ─────────────────────────────────────────────

    @Override
    public void handleRejection(DocumentDraft draft, String reason) {
        log.warn("""
            Document rejected for repository: {}
            Reason: {}
            Workflow will terminate.
            """, draft.repoName(), reason);
    }

    @Override
    public Map<String, ModuleNarrative> loadCachedNarratives(String repoName, List<ModuleInfo> modules) {
        Map<String, ProcessedModule> stored = processedModuleRepository
                .findByRepoName(repoName)
                .stream()
                .collect(Collectors.toMap(ProcessedModule::getFilePath, m -> m));

        Map<String, ModuleNarrative> cache = new HashMap<>();

        for (ModuleInfo module : modules) {
            ProcessedModule record = stored.get(module.filePath());
            if (record == null) continue; // never seen before — needs LLM

            String currentHash = computeHash(module.sourceCode());
            if (!currentHash.equals(record.getContentHash())) continue; // changed — needs LLM

            try {
                ModuleNarrative narrative = objectMapper.readValue(
                        record.getNarrativeJson(), ModuleNarrative.class
                );
                cache.put(module.filePath(), narrative);
                log.info("Cache hit — skipping LLM for: {}", module.name());
            } catch (Exception e) {
                log.warn("Failed to deserialise cached narrative for: {}", module.name());
            }
        }

        log.info("Cache: {}/{} modules unchanged, {} need LLM",
                cache.size(), modules.size(), modules.size() - cache.size());
        return cache;
    }

    @Override
    public void saveModuleCache(String repoName, List<ModuleInfo> modules, List<ModuleNarrative> narratives) {
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo module = modules.get(i);
            ModuleNarrative narrative = narratives.get(i);

            try {
                String hash = computeHash(module.sourceCode());
                String narrativeJson = objectMapper.writeValueAsString(narrative);

                // Delete existing record if present, then insert fresh
                processedModuleRepository
                        .findByRepoNameAndFilePath(repoName, module.filePath())
                        .ifPresent(processedModuleRepository::delete);

                processedModuleRepository.save(new ProcessedModule(
                        repoName,
                        module.filePath(),
                        hash,
                        narrativeJson,
                        Instant.now()
                ));
            } catch (Exception e) {
                log.warn("Failed to cache module: {}", module.name(), e);
            }
        }
        log.info("Saved module cache for {} modules in repo: {}", modules.size(), repoName);
    }

    // ═════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════

    private String extractRepoName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        return parts[parts.length - 1].replace(".git", "");
    }

    private String cloneRepository(String repoUrl, String repoName) {
        if (!repoUrl.startsWith("http")) {
            return repoUrl;
        }
        try {
            Path localPath = Files.createTempDirectory("checkpoint-");
            Path repoPath = localPath.resolve(repoName);

            ProcessBuilder pb = new ProcessBuilder("git", "clone", repoUrl, repoPath.toString());
            pb.redirectErrorStream(true);

            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("git clone failed with exit code: " + exitCode);
            }

            return repoPath.toString();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to clone repository: " + repoUrl, e);
        }
    }

    private boolean isTestFile(Path file) {
        String path = file.toString();
        return path.contains("/test/")
                || path.contains("/tests/")
                || file.getFileName().toString().endsWith("Test.java")
                || file.getFileName().toString().endsWith("Tests.java");
    }

    private String extractClassName(Path file) {
        String filename = file.getFileName().toString();
        return filename.replace(".java", "");
    }

    private String extractPackageName(String sourceCode) {
        return Arrays.stream(sourceCode.split("\n"))
                .filter(line -> line.trim().startsWith("package "))
                .findFirst()
                .map(line -> line.trim()
                        .replace("package ", "")
                        .replace(";", ""))
                .orElse("default");
    }

    private List<String> extractImports(String sourceCode) {
        return Arrays.stream(sourceCode.split("\n"))
                .filter(line -> line.trim().startsWith("import "))
                .map(line -> line.trim()
                        .replace("import ", "")
                        .replace(";", ""))
                .collect(Collectors.toList());
    }

    /**
     * Calculates cyclomatic complexity for a single method.
     * Counts decision points: if, else if, while, for, case, catch,
     * ternary, &&, ||
     */
    private int calculateMethodComplexity(MethodDeclaration method) {
        String body = method.toString();
        int complexity = 1; // base complexity

        // Count decision points
        complexity += countOccurrences(body, "if (");
        complexity += countOccurrences(body, "if(");
        complexity += countOccurrences(body, "else if");
        complexity += countOccurrences(body, "while (");
        complexity += countOccurrences(body, "while(");
        complexity += countOccurrences(body, "for (");
        complexity += countOccurrences(body, "for(");
        complexity += countOccurrences(body, "case ");
        complexity += countOccurrences(body, "catch (");
        complexity += countOccurrences(body, "catch(");
        complexity += countOccurrences(body, " && ");
        complexity += countOccurrences(body, " || ");
        complexity += countOccurrences(body, " ? ");

        return complexity;
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    // ── PROMPT BUILDERS ───────────────────────────────────────────────

    private String buildExplainModulePrompt(ModuleInfo module,
                                            ComplexityReport complexity) {
        return """
            You are a senior software architect documenting a Java codebase.
            Analyse the following Java class and provide a structured explanation.

            CLASS NAME: %s
            PACKAGE: %s

            COMPLEXITY METRICS (treat these as facts — do not estimate):
            - Cyclomatic complexity: %d
            - Risk level: %s
            - Lines of code: %d
            - Method count: %d
            - Most complex method: %s (complexity: %d)
            - Dependencies: %s

            SOURCE CODE:
            %s

            Provide your analysis in exactly this format:
            PURPOSE: [one sentence describing what this class does]
            COMPLEXITY_EXPLANATION: [why the complexity is at this level]
            DESIGN_PATTERNS: [comma-separated list of patterns, or NONE]
            CONCERNS: [comma-separated list of concerns, or NONE]
            REFACTORING_SUGGESTIONS: [comma-separated list, or NONE]
            """.formatted(
                module.name(),
                module.packageName(),
                complexity.cyclomaticComplexity(),
                complexity.riskLevel(),
                complexity.linesOfCode(),
                complexity.methodCount(),
                complexity.mostComplexMethod(),
                complexity.maxMethodComplexity(),
                String.join(", ", complexity.dependencies()),
                module.sourceCode()
        );
    }

    private String buildArchitecturePrompt(
            List<ModuleNarrative> narratives,
            List<ComplexityReport> complexityReports) {

        StringBuilder modulesSummary = new StringBuilder();
        for (int i = 0; i < narratives.size(); i++) {
            ModuleNarrative n = narratives.get(i);
            ComplexityReport c = complexityReports.get(i);
            modulesSummary.append("""
                Module: %s
                Purpose: %s
                Risk: %s
                Complexity: %d
                Dependencies: %s
                Concerns: %s

                """.formatted(
                    n.moduleName(),
                    n.purpose(),
                    c.riskLevel(),
                    c.cyclomaticComplexity(),
                    String.join(", ", c.dependencies()),
                    String.join(", ", n.concerns())
            ));
        }

        return """
            You are a principal architect reviewing a complete Java codebase.
            Based on the module summaries below, provide an architectural analysis.

            MODULES:
            %s

            Provide your analysis in exactly this format:
            ARCHITECTURAL_STYLE: [monolith/modular-monolith/microservices/other]
            DETECTED_PATTERNS: [comma-separated list]
            ANTI_PATTERNS: [comma-separated list, or NONE]
            COUPLING_CONCERNS: [comma-separated list, or NONE]
            PRIMARY_RISK: [single most important risk]
            MIGRATION_PATH: [concrete next step if needed, or NONE]
            MIGRATION_WEEKS: [integer estimate, or 0]
            """.formatted(modulesSummary);
    }

    private String buildAssembleDocumentPrompt(
            List<ModuleNarrative> narratives,
            ArchitecturalAnalysis analysis,
            RepositoryInfo repoInfo) {

        String moduleSection = narratives.stream()
                .map(n -> """
                ### %s
                %s

                **Design Patterns:** %s
                **Concerns:** %s
                **Refactoring Suggestions:** %s
                """.formatted(
                        n.moduleName(),
                        n.purpose(),
                        String.join(", ", n.designPatterns()),
                        String.join(", ", n.concerns()),
                        String.join(", ", n.refactoringSuggestions())
                ))
                .collect(Collectors.joining("\n"));

        return """
            You are a technical writer assembling an architecture document.
            Create a professional, readable markdown document from the
            analysis below.

            REPOSITORY: %s
            TOTAL FILES: %d
            TOTAL LINES: %d

            ARCHITECTURAL ANALYSIS:
            Style: %s
            Patterns: %s
            Anti-patterns: %s
            Primary Risk: %s
            Migration Path: %s

            MODULE ANALYSES:
            %s

            Write a complete architecture document with these sections:
            1. Executive Summary
            2. Architecture Overview
            3. Module Documentation
            4. Risks and Concerns
            5. Recommendations

            Use clear markdown formatting. Be specific and actionable.
            """.formatted(
                repoInfo.repoName(),
                repoInfo.totalFiles(),
                repoInfo.totalLinesOfCode(),
                analysis.architecturalStyle(),
                String.join(", ", analysis.detectedPatterns()),
                String.join(", ", analysis.antiPatterns()),
                analysis.primaryRisk(),
                analysis.migrationPath(),
                moduleSection
        );
    }

    // ── RESPONSE PARSERS ──────────────────────────────────────────────

    private ModuleNarrative parseModuleNarrative(String moduleName,
                                                 String response) {
        Map<String, String> fields = parseStructuredResponse(response);

        return new ModuleNarrative(
                moduleName,
                fields.getOrDefault("PURPOSE", "Not available"),
                fields.getOrDefault("COMPLEXITY_EXPLANATION", "Not available"),
                parseList(fields.getOrDefault("DESIGN_PATTERNS", "NONE")),
                parseList(fields.getOrDefault("CONCERNS", "NONE")),
                parseList(fields.getOrDefault("REFACTORING_SUGGESTIONS", "NONE")),
                0, // token counts — Spring AI Micrometer handles this
                0
        );
    }

    private ArchitecturalAnalysis parseArchitecturalAnalysis(String response) {
        Map<String, String> fields = parseStructuredResponse(response);

        return new ArchitecturalAnalysis(
                fields.getOrDefault("ARCHITECTURAL_STYLE", "unknown"),
                parseList(fields.getOrDefault("DETECTED_PATTERNS", "NONE")),
                parseList(fields.getOrDefault("ANTI_PATTERNS", "NONE")),
                parseList(fields.getOrDefault("COUPLING_CONCERNS", "NONE")),
                fields.getOrDefault("PRIMARY_RISK", "Not identified"),
                fields.getOrDefault("MIGRATION_PATH", "NONE"),
                parseIntSafely(fields.getOrDefault("MIGRATION_WEEKS", "0"))
        );
    }

    private Map<String, String> parseStructuredResponse(String response) {
        Map<String, String> fields = new HashMap<>();
        for (String line : response.split("\n")) {
            int colonIndex = line.indexOf(":");
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                fields.put(key, value);
            }
        }
        return fields;
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NONE")) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private int parseIntSafely(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String computeHash(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}