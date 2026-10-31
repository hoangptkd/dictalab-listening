package com.dictalab.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Repository-owned immutable definition. No user prompt or runtime model selection belongs here. */
public record ScenarioProgram(String schemaVersion, String code, int version, Status status, String displayNameVi,
        List<TargetScale> targetScales, List<String> supportedPracticeModes, List<String> supportedQuestionTypes,
        List<DifficultyProfile> difficultyProfiles, String skillTaxonomyVersion, GenerationPolicy generationPolicy,
        GradingPolicy gradingPolicy, SupportPolicy supportPolicy, String qualityPolicyVersion,
        List<ArtifactRef> promptArtifactRefs, String definitionHash) {
    public enum Status { DRAFT, APPROVED, RETIRED }
    public record ArtifactRef(String purpose, String artifactId, String sha256) {}
    public record GenerationPolicy(int deadlineSeconds, int maxCalls, int maxInputChars, int maxOutputTokens,
                                   int maxResultBytes, int repairLimit) {}
    public record GradingPolicy(int maxAttempts, String uncertaintyPolicy) {}
    public record SupportPolicy(boolean replay, boolean slow, boolean transcript, boolean translation, boolean reveal) {}
    public record DifficultyProfile(String id, int version, int minWordsPerActivity, int maxWordsPerActivity,
            int maxWordsPerSession, int maxActivities, int maxSpeakers, int maxSegmentsPerActivity,
            int maxQuestionsPerSession, int maxFactsPerActivity, int maxDistractors, int maxCorrections,
            String inferencePolicy, int speechRateWpm, int pauseMillis, String vocabularyGuidance,
            String syntaxGuidance, String informationDensity, List<String> skills, List<String> allowedVoiceProfiles) {
        public DifficultyProfile {
            skills = List.copyOf(skills);
            allowedVoiceProfiles = List.copyOf(allowedVoiceProfiles);
        }
    }
    public record TargetScale(String scaleId, String component, String min, String max, String step,
                              List<String> labels, String sourceUrl, String reviewedOn) {
        public TargetScale { labels = List.copyOf(labels); }
        public void validateValue(String value) {
            if (!labels.isEmpty()) {
                require(labels.contains(value), "Unknown target label");
                return;
            }
            require(value != null && value.matches("(?:0|[1-9][0-9]{0,3})(?:\\.[0-9])?"), "Invalid decimal target");
            var number = new BigDecimal(value);
            var lower = new BigDecimal(min);
            require(number.compareTo(lower) >= 0 && number.compareTo(new BigDecimal(max)) <= 0
                    && number.subtract(lower).remainder(new BigDecimal(step)).signum() == 0, "Target outside scale");
        }
    }

    public ScenarioProgram {
        targetScales = List.copyOf(targetScales);
        supportedPracticeModes = List.copyOf(supportedPracticeModes);
        supportedQuestionTypes = List.copyOf(supportedQuestionTypes);
        difficultyProfiles = List.copyOf(difficultyProfiles);
        promptArtifactRefs = List.copyOf(promptArtifactRefs);
        require("scenario-program.v1".equals(schemaVersion), "Unknown program schema");
        require(code != null && code.matches("[a-z][a-z0-9-]{0,63}") && version > 0, "Invalid program reference");
        require(status != null && displayNameVi != null && !displayNameVi.isBlank() && displayNameVi.length() <= 120,
                "Missing program metadata");
        require(definitionHash != null && definitionHash.matches("[a-f0-9]{64}"), "Missing definition hash");
        require(skillTaxonomyVersion != null && qualityPolicyVersion != null, "Missing policy versions");
        require(!targetScales.isEmpty() && targetScales.size() <= 8, "Missing target scales");
        var scaleIds = new java.util.HashSet<String>();
        for (var scale : targetScales) {
            require(scale.scaleId() != null && scale.scaleId().matches("[a-z][a-z0-9-]{0,63}")
                    && scaleIds.add(scale.scaleId()), "Duplicate/invalid scale");
            require(scale.component() != null && scale.component().matches("[A-Z][A-Z_]{0,31}"), "Invalid component");
            require(scale.labels() != null && scale.labels().size() <= 20, "Invalid labels");
            if (scale.labels().isEmpty()) {
                require(scale.min() != null && scale.max() != null && scale.step() != null, "Missing numeric scale");
                require(new BigDecimal(scale.step()).signum() > 0, "Invalid scale step");
                scale.validateValue(scale.min()); scale.validateValue(scale.max());
            } else {
                require(scale.min() == null && scale.max() == null && scale.step() == null
                        && Set.copyOf(scale.labels()).size() == scale.labels().size(), "Mixed target scale");
                require(scale.labels().stream().allMatch(s -> s.matches("[a-z][a-z0-9-]{0,31}")), "Invalid label");
            }
        }
        require(generationPolicy != null && generationPolicy.deadlineSeconds() > 0
                && generationPolicy.deadlineSeconds() <= 300 && generationPolicy.maxCalls() > 0
                && generationPolicy.maxCalls() <= 7 && generationPolicy.maxInputChars() > 0
                && generationPolicy.maxInputChars() <= 2000 && generationPolicy.maxOutputTokens() > 0
                && generationPolicy.maxOutputTokens() <= 16000 && generationPolicy.maxResultBytes() > 0
                && generationPolicy.maxResultBytes() <= 262144 && generationPolicy.repairLimit() >= 0
                && generationPolicy.repairLimit() <= 1, "Invalid generation bounds");
        require(gradingPolicy != null && gradingPolicy.maxAttempts() > 0 && gradingPolicy.maxAttempts() <= 3
                && "KEEP_UNCERTAIN".equals(gradingPolicy.uncertaintyPolicy()) && supportPolicy != null, "Invalid grading policy");
        require(supportedPracticeModes.stream().allMatch(Set.of("guided-listening", "exam-practice")::contains)
                && supportedQuestionTypes.stream().allMatch(Set.of("MEANING_RECALL_VI", "SINGLE_CHOICE", "SHORT_ANSWER_EN")::contains),
                "Unknown mode/question type");
        var profileIds = new java.util.HashSet<String>();
        require(difficultyProfiles.size() <= 10, "Too many profiles");
        for (var p : difficultyProfiles) {
            require(p.id() != null && p.id().matches("[a-z][a-z0-9-]{0,31}") && profileIds.add(p.id())
                    && p.version() > 0 && p.minWordsPerActivity() > 0 && p.maxWordsPerActivity() >= p.minWordsPerActivity()
                    && p.maxWordsPerActivity() <= p.maxWordsPerSession() && p.maxWordsPerSession() <= 480
                    && p.maxActivities() >= 1 && p.maxActivities() <= 4 && p.maxSpeakers() >= 1 && p.maxSpeakers() <= 3
                    && p.maxSegmentsPerActivity() >= p.maxSpeakers() && p.maxSegmentsPerActivity() <= 6
                    && p.maxQuestionsPerSession() >= 1 && p.maxQuestionsPerSession() <= 12
                    && p.maxFactsPerActivity() >= 1 && p.maxFactsPerActivity() <= 4
                    && p.maxDistractors() >= 0 && p.maxDistractors() <= 3 && p.maxCorrections() >= 0 && p.maxCorrections() <= 2
                    && Set.of("EXPLICIT_ONLY", "SUPPORTED_INFERENCE").contains(p.inferencePolicy())
                    && p.speechRateWpm() >= 80 && p.speechRateWpm() <= 200 && p.pauseMillis() >= 0 && p.pauseMillis() <= 3000
                    && p.vocabularyGuidance() != null && p.syntaxGuidance() != null && p.informationDensity() != null
                    && p.skills() != null && !p.skills().isEmpty() && p.allowedVoiceProfiles() != null, "Invalid difficulty profile");
        }
        for (var ref : promptArtifactRefs) require(ref.purpose() != null && ref.artifactId() != null
                && ref.sha256() != null && ref.sha256().matches("[a-f0-9]{64}"), "Invalid prompt artifact");
        if (status == Status.APPROVED) {
            require(!difficultyProfiles.isEmpty() && !supportedPracticeModes.isEmpty() && !supportedQuestionTypes.isEmpty(),
                    "Approved program lacks capability");
            require(promptArtifactRefs.stream().map(ArtifactRef::purpose).collect(java.util.stream.Collectors.toSet())
                    .containsAll(Set.of("extraction", "generation", "translation", "review", "grading")), "Missing approved artifacts");
            require(targetScales.stream().allMatch(s -> s.sourceUrl() != null && s.sourceUrl().startsWith("https://")
                    && s.reviewedOn() != null), "Missing scale review");
        }
    }

    public void validateTarget(ScenarioGoalRequest.Target target) {
        require(target != null, "Missing target");
        targetScales.stream().filter(s -> s.scaleId().equals(target.scaleId()) && s.component().equals(target.component()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Scale/component mismatch")).validateValue(target.value());
    }

    static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
