package io.github.jrdesai.checkpoint_ai.domain.model;

import java.util.List;

/**
 * Represents a single analysable Java module within the repository.
 */

public record ModuleInfo(
        String name,
        String packageName,
        String sourceCode,
        String filePath,
        int lineCount,
        List<String> imports
) {}
