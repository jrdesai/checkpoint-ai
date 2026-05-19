package io.github.jrdesai.checkpoint_ai.activity;

import io.github.jrdesai.checkpoint_ai.domain.model.ComplexityReport;
import io.github.jrdesai.checkpoint_ai.domain.model.ModuleInfo;
import io.github.jrdesai.checkpoint_ai.domain.model.RiskLevel;
import io.github.jrdesai.checkpoint_ai.persistence.ProcessedModuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ComplexityAnalyserTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ProcessedModuleRepository processedModuleRepository;

    private CodebaseDocumentationActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        activities = new CodebaseDocumentationActivitiesImpl(chatClient, processedModuleRepository);
        ReflectionTestUtils.setField(activities, "modelName", "gemini-test");
    }

    @Test
    void simpleMethodHasBaseComplexityOfOne() {
        ModuleInfo module = moduleWithSource("""
                public class Simple {
                    public String getName() {
                        return "hello";
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.cyclomaticComplexity()).isEqualTo(1);
        assertThat(report.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void ifStatementIncreasesComplexityByOne() {
        ModuleInfo module = moduleWithSource("""
                public class WithIf {
                    public String check(boolean flag) {
                        if (flag) {
                            return "yes";
                        }
                        return "no";
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.cyclomaticComplexity()).isEqualTo(2);
    }

    @Test
    void nestedIfStatementsAccumulateComplexity() {
        ModuleInfo module = moduleWithSource("""
                public class Nested {
                    public int calculate(int x, int y) {
                        if (x > 0) {
                            if (y > 0) {
                                return x + y;
                            }
                            return x;
                        }
                        return y;
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.cyclomaticComplexity()).isEqualTo(3);
    }

    @Test
    void logicalOperatorsIncreaseComplexity() {
        ModuleInfo module = moduleWithSource("""
                public class WithLogic {
                    public boolean check(int x, int y, int z) {
                        return x > 0 && y > 0 || z > 0;
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        // base 1 + && + || = 3
        assertThat(report.cyclomaticComplexity()).isEqualTo(3);
    }

    @Test
    void highComplexityClassIsRatedAsHighRisk() {
        ModuleInfo module = moduleWithSource("""
                public class Complex {
                    public String process(int x) {
                        if (x == 1) { return "one"; }
                        else if (x == 2) { return "two"; }
                        else if (x == 3) { return "three"; }
                        else if (x == 4) { return "four"; }
                        else if (x == 5) { return "five"; }
                        else if (x == 6) { return "six"; }
                        else if (x == 7) { return "seven"; }
                        else if (x == 8) { return "eight"; }
                        else if (x == 9) { return "nine"; }
                        else if (x == 10) { return "ten"; }
                        else if (x == 11) { return "eleven"; }
                        return "other";
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void mostComplexMethodIsIdentified() {
        ModuleInfo module = moduleWithSource("""
                public class TwoMethods {
                    public String simple() {
                        return "hello";
                    }
                    public String complex(boolean a, boolean b) {
                        if (a && b) {
                            return "both";
                        }
                        return "neither";
                    }
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.mostComplexMethod()).isEqualTo("complex");
    }

    @Test
    void methodCountIsAccurate() {
        ModuleInfo module = moduleWithSource("""
                public class ThreeMethods {
                    public void one() {}
                    public void two() {}
                    public void three() {}
                }
                """);

        ComplexityReport report = activities.analyseComplexity(module);

        assertThat(report.methodCount()).isEqualTo(3);
    }

    // ── Helper ────────────────────────────────────────────────────────

    private ModuleInfo moduleWithSource(String sourceCode) {
        return new ModuleInfo(
                "TestModule",
                "io.test",
                sourceCode,
                "/test/TestModule.java",
                sourceCode.split("\n").length,
                List.of()
        );
    }
}