package com.dictalab.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Product constraints for Conversation tasks, not CEFR proficiency thresholds. */
enum ConversationLevelProfile {
    A1(8, 35, 2, 2, "Thông tin trực tiếp, câu ngắn và quen thuộc. Có thể nghe chậm."),
    A2(18, 55, 2, 3, "Nối thông tin qua vài câu; nghe ý chính và lý do rõ ràng."),
    B1(30, 90, 2, 3, "Hiểu ý chính, lý do, điều kiện, thái độ rõ ràng và suy luận đơn giản có căn cứ trong ngữ cảnh quen thuộc.");

    static final List<String> SUPPORTED_LEVELS = Arrays.stream(values()).map(Enum::name).toList();
    static final Map<String, Integer> LEGACY_BOUNDS = Map.of("minWords", 20, "maxWords", 65);
    static final Map<String, Integer> WARMUP_BOUNDS = Map.of("minWords", 8, "maxWords", 55);

    private final Map<String, Integer> wordBounds;
    private final int minSlots;
    private final int maxSlots;
    private final String descriptionVi;

    ConversationLevelProfile(int minWords, int maxWords, int minSlots, int maxSlots, String descriptionVi) {
        // Keep the existing few-shot serialization order for prompt caching and paired-seed runs.
        var bounds = new LinkedHashMap<String, Integer>();
        bounds.put("maxWords", maxWords);
        bounds.put("minWords", minWords);
        this.wordBounds = Collections.unmodifiableMap(bounds);
        this.minSlots = minSlots;
        this.maxSlots = maxSlots;
        this.descriptionVi = descriptionVi;
    }

    static ConversationLevelProfile require(String level) {
        if (level == null || !SUPPORTED_LEVELS.contains(level)) throw new IllegalArgumentException("Unsupported conversation level");
        return valueOf(level);
    }

    Map<String, Integer> wordBounds() { return wordBounds; }
    int minSlots() { return minSlots; }
    int maxSlots() { return maxSlots; }
    String descriptionVi() { return descriptionVi; }

    List<String> taskTypes() {
        if (this == A1) return List.of("direct_detail");
        var types = new java.util.ArrayList<>(List.of("direct_detail", "gist", "reason_purpose",
                "change_revision", "instruction_sequence", "condition"));
        if (this == B1) types.addAll(List.of("opinion_attitude", "inference_prediction"));
        return List.copyOf(types);
    }

    Map<String, String> skills() {
        var skills = new LinkedHashMap<String, String>();
        for (String key : List.of("condition", "change", "gist", "reason", "instructions", "details"))
            skills.put(key, ConversationContent.SKILLS.get(key));
        if (this == B1) for (String key : List.of("attitude", "inference"))
            skills.put(key, ConversationContent.SKILLS.get(key));
        return Collections.unmodifiableMap(skills);
    }
}
