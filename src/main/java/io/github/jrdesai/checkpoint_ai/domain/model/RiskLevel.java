package io.github.jrdesai.checkpoint_ai.domain.model;

/**
 * Risk level based on cyclomatic complexity thresholds.
 * LOW: 1-5, MEDIUM: 6-10, HIGH: 11-20, CRITICAL: 21+
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static RiskLevel fromCyclomaticComplexity(int complexity) {
        if (complexity <= 5)  return LOW;
        if (complexity <= 10) return MEDIUM;
        if (complexity <= 20) return HIGH;
        return CRITICAL;
    }
}