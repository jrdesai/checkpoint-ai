package io.github.jrdesai.checkpoint_ai.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * Static analysis metrics for a single module.
 */
public record ComplexityReport(
        String moduleName,
        int cyclomaticComplexity,
        int cognitiveComplexity,
        int linesOfCode,
        int methodCount,
        String mostComplexMethod,
        int maxMethodComplexity,
        Map<String, Integer> methodComplexityMap,
        List<String> dependencies,
        int dependencyCount,
        RiskLevel riskLevel
) {
    /**
     * Industry threshold: cyclomatic complexity above 10 is high risk.
     */
    @JsonIgnore
    public boolean isHighRisk() {
        return cyclomaticComplexity > 10;
    }

    /**
     * How far above the safe threshold the most complex method is.
     */
    @JsonIgnore
    public int complexityExcess() {
        return Math.max(0, maxMethodComplexity - 10);
    }
}