package io.github.jrdesai.checkpoint_ai.domain.model;

import java.util.List;

/**
 * LLM-generated architectural analysis across all modules.
 */
public record ArchitecturalAnalysis(
        String architecturalStyle,
        List<String> detectedPatterns,
        List<String> antiPatterns,
        List<String> couplingConcerns,
        String primaryRisk,
        String migrationPath,
        int estimatedMigrationWeeks
) {}