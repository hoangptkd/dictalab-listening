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
    private final String personalizedPromptHash;
    private final String assessmentPromptHash;
    private final org.springframework.core.env.Environment environment;

    @org.springframework.beans.factory.annotation.Autowired
    public ScenarioRelease(ScenarioProperties rollout, ScenarioEngineProperties engine, ScenarioProgramRegistry programs,
                           AiPlatformClient ai, org.springframework.core.env.Environment environment) throws IOException {
        this.rollout = rollout; this.engine = engine; this.programs = programs; this.ai = ai;
        this.environment = environment;
        try (var input = new ClassPathResource("scenario/workflow-v1.sha256").getInputStream()) {
            promptHash = new String(input.readNBytes(66), StandardCharsets.US_ASCII).trim();
        }
        if (!promptHash.matches("[a-f0-9]{64}")) throw new IllegalStateException("Invalid scenario workflow pin");
        try (var input = new ClassPathResource("scenario/workflow-v2.sha256").getInputStream()) {
            personalizedPromptHash = new String(input.readNBytes(66), StandardCharsets.US_ASCII).trim();
        }
        if (!personalizedPromptHash.matches("[a-f0-9]{64}")) throw new IllegalStateException("Invalid personalized workflow pin");
        String defaultAssessmentHash;
        try (var input = new ClassPathResource("scenario/workflow-v3.sha256").getInputStream()) {
            defaultAssessmentHash = new String(input.readNBytes(66), StandardCharsets.US_ASCII).trim();
        }
        if (!defaultAssessmentHash.equals(ScenarioAssessment.PROMPT_HASH)) throw new IllegalStateException("Invalid assessment workflow pin");
        // Explicit local preview approval selects the candidate; saved lessons retain their own pins.
        boolean directAuditPreview = engine.localPreviewEnabled()
                && ScenarioAssessment.PARTS_V4_12_PROMPT_HASH.equals(engine.approvedPromptHash())
                && environment.matchesProfiles("local") && !environment.matchesProfiles("prod", "production")
                && Set.of("127.0.0.1", "::1").contains(environment.getProperty("server.address", ""));
        assessmentPromptHash = directAuditPreview ? ScenarioAssessment.PARTS_V4_12_PROMPT_HASH : defaultAssessmentHash;
    }

    ScenarioRelease(ScenarioProperties rollout, ScenarioEngineProperties engine, ScenarioProgramRegistry programs,
                    AiPlatformClient ai) throws IOException {
        this(rollout,engine,programs,ai,new org.springframework.core.env.StandardEnvironment());
    }

    public boolean available(long user) { return available() || localPreview(user); }
    public String generationMode(long user) { return available() ? "APPROVED" : localPreview(user) ? "LOCAL_PREVIEW" : "DISABLED"; }
    private boolean localPreview(long user) {
        if (!rollout.enabled() || !engine.enabled() || !engine.localPreviewEnabled()
                || !engine.localPreviewUsers().contains(user) || !ai.textConfigured()
                || !environment.matchesProfiles("local") || environment.matchesProfiles("prod", "production")
                || !Set.of("127.0.0.1","::1").contains(environment.getProperty("server.address", ""))
                || !promptHash.equals(engine.approvedPromptHash())) return false;
        try {
            return program().status() == ScenarioProgram.Status.DRAFT
                    && program().definitionHash().equals(engine.approvedProgramHash());
        } catch (IllegalArgumentException missing) { return false; }
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
    public String personalizedPromptHash() { return personalizedPromptHash; }
    public String assessmentPromptHash() { return assessmentPromptHash; }
    public java.util.List<ScenarioProgram> generationPrograms(long user) {
        return programs.definitions().stream().filter(p -> available(user,p)).toList();
    }
    public boolean available(long user, ScenarioProgram program) {
        if (program.version() < 2) return program.equals(program()) && available(user);
        String selectedHash=program.meaningAssessment() ? assessmentPromptHash : personalizedPromptHash;
        if (!rollout.enabled() || !engine.enabled() || !ai.textConfigured()
                || !selectedHash.equals(engine.approvedPromptHash())
                || !program.definitionHash().equals(engine.approvedPrograms().get(program.code()+":"+program.version()))) return false;
        if (!program.supportedPracticeModes().contains("guided-listening")
                || !program.supportedQuestionTypes().contains("MEANING_RECALL_VI")) return false;
        if (program.status() == ScenarioProgram.Status.APPROVED)
            return program.promptArtifactRefs().stream().allMatch(a -> a.sha256().equals(selectedHash)
                    && a.artifactId().equals(program.meaningAssessment() ? "scenario-listening-v3" : "scenario-listening-v2"));
        return program.status() == ScenarioProgram.Status.DRAFT && engine.localPreviewEnabled()
                && engine.localPreviewUsers().contains(user) && environment.matchesProfiles("local")
                && !environment.matchesProfiles("prod","production")
                && Set.of("127.0.0.1","::1").contains(environment.getProperty("server.address",""));
    }
    public ScenarioProgram require(long user, String code, int version) {
        ScenarioProgram program;
        try { program=programs.require(code,version); }
        catch (IllegalArgumentException missing) { throw new ScenarioFailure(400,"UNSUPPORTED_PROGRAM_MODE","Chương trình không tồn tại."); }
        if (!available(user,program)) throw new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Chương trình chưa mở để tạo bài.");
        return program;
    }
    public void requireGrade(long user,String code,int version,String definitionHash) {
        requireSavedProgram(user,code,version,definitionHash);
    }
    public void requireAudio(long user,String code,int version,String definitionHash) {
        requireSavedProgram(user,code,version,definitionHash);
    }
    private void requireSavedProgram(long user,String code,int version,String definitionHash) {
        if (!rollout.enabled() || !engine.enabled() || !ai.textConfigured())
            throw new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Saved lesson services are not currently available.");
        ScenarioProgram program;
        try { program=programs.require(code,version); }
        catch (IllegalArgumentException missing) { throw new ScenarioFailure(409,"WORKFLOW_PROVENANCE_MISSING","Historical program is unavailable."); }
        if (!program.definitionHash().equals(definitionHash)) throw new ScenarioFailure(409,"WORKFLOW_PROVENANCE_MISSING","Historical program does not match.");
        if (program.status()!=ScenarioProgram.Status.APPROVED && !(engine.localPreviewEnabled() && engine.localPreviewUsers().contains(user)
                && environment.matchesProfiles("local") && !environment.matchesProfiles("prod","production")
                && Set.of("127.0.0.1","::1").contains(environment.getProperty("server.address",""))))
            throw new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Historical preview services are unavailable.");
    }
    public void require() {
        if (!available()) throw new ScenarioFailure(503, "UNSUPPORTED_PROGRAM_MODE", "Chương trình chưa qua đủ kiểm tra để tạo bài. Bạn vẫn có thể mở bài đã lưu.");
    }
    public void require(long user) {
        if (!available(user)) throw new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Chương trình chưa mở cho tài khoản này. Bạn vẫn có thể mở bài đã lưu.");
    }
}
