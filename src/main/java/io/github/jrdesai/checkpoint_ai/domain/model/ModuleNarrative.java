package io.github.jrdesai.checkpoint_ai.domain.model;

import java.util.List;

/**
 * LLM-generated explanation of a single module.
 */
public record ModuleNarrative(
        String moduleName,
        String purpose,
        String complexityExplanation,
        List<String> designPatterns,
        List<String> concerns,
        List<String> refactoringSuggestions,
        int inputTokensUsed,
        int outputTokensUsed
) {}