package io.github.jrdesai.checkpoint_ai.domain.model;

import java.util.List;

/***
 * Represents a cloned repository to be used for analysis
 */
public record RepositoryInfo(

        String repoUrl,
        String repoName,
        String localPath,
        List<ModuleInfo> modules,
        int totalFiles,
        int totalLinesOfCode
) {}
