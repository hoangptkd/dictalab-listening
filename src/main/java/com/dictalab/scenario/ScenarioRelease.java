package com.dictalab.scenario;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.dictalab.service.AiPlatformClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class ScenarioRelease {
    private final ScenarioProperties rollout;
    private final ScenarioEngineProperties engine;
    private final ScenarioProgramRegistry programs;
    private final AiPlatformClient ai;
    private final String promptHash;

    public ScenarioRelease(ScenarioProperties rollout, ScenarioEngineProperties engine, ScenarioProgramRegistry programs,
                           AiPlatformClient ai) throws IOException {
        this.rollout = rollout; this.engine = engine; this.programs = programs; this.ai = ai;
        try (var input = new ClassPathResource("scenario/workflow-v1.sha256").getInputStream()) {
            promptHash = new String(input.readNBytes(66), StandardCharsets.US_ASCII).trim();
        }
        if (!promptHash.matches("[a-f0-9]{64}")) throw new IllegalStateException("Invalid scenario workflow pin");
    }

    public boolean available() {
        if (!rollout.enabled() || !engine.enabled() || !ai.textConfigured() || !promptHash.equals(engine.approvedPromptHash())) return false;
        try {
            var program = program();
            return program.status() == ScenarioProgram.Status.APPROVED
                    && program.definitionHash().equals(engine.approvedProgramHash())
                    && program.supportedPracticeModes().contains("guided-listening")
                    && program.supportedQuestionTypes().contains("MEANING_RECALL_VI")
                    && program.promptArtifactRefs().stream().filter(a -> Set.of("extraction","generation","translation","review","grading").contains(a.purpose()))
                        .allMatch(a -> a.sha256().equals(promptHash) && a.artifactId().equals("scenario-listening-v1"));
        } catch (IllegalArgumentException missing) { return false; }
    }

    public ScenarioProgram program() { return programs.require("general-communication", engine.programVersion()); }
    public String promptHash() { return promptHash; }
    public void require() {
        if (!available()) throw new ScenarioFailure(503, "UNSUPPORTED_PROGRAM_MODE", "Chương trình chưa qua đủ kiểm tra để tạo bài. Bạn vẫn có thể mở bài đã lưu.");
    }
}
