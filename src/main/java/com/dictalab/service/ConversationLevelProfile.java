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
    B1(30, 90, 2, 3, "Hiểu ý chính, lý do, điều kiện, thái độ rõ ràng và suy luận đơn giản có căn cứ trong ngữ cảnh quen thuộc."),
    // Compact shadow comparisons reuse B1 bounds; these are not finalized B2/C1 lengths.
    B2(Status.SHADOW, 30, 90, "Theo dõi lập luận, tương phản và quan điểm có căn cứ."),
    C1(Status.SHADOW, 30, 90, "Diễn giải quan hệ ẩn, ý định và lập trường trong ngữ cảnh phức tạp."),
    C2(Status.PLANNED, 0, 0, "Rubric hàm ý và sắc thái đang được thiết kế; chưa cho phép sinh bài.");

    enum Status { SUPPORTED, SHADOW, PLANNED }
    static final List<String> SUPPORTED_LEVELS = Arrays.stream(values())
            .filter(p -> p.status == Status.SUPPORTED).map(Enum::name).toList();
    static final Map<String, Integer> LEGACY_BOUNDS = Map.of("minWords", 20, "maxWords", 65);
    static final Map<String, Integer> WARMUP_BOUNDS = Map.of("minWords", 8, "maxWords", 55);

    private final Map<String, Integer> wordBounds;
    private final int minSlots;
    private final int maxSlots;
    private final String descriptionVi;
    private final Status status;

    ConversationLevelProfile(int minWords, int maxWords, int minSlots, int maxSlots, String descriptionVi) {
        this(Status.SUPPORTED, minWords, maxWords, minSlots, maxSlots, descriptionVi);
    }

    ConversationLevelProfile(Status status, int minWords, int maxWords, String descriptionVi) {
        this(status, minWords, maxWords, 2, 3, descriptionVi);
    }

    ConversationLevelProfile(Status status, int minWords, int maxWords, int minSlots, int maxSlots, String descriptionVi) {
        // Keep the existing few-shot serialization order for prompt caching and paired-seed runs.
        var bounds = new LinkedHashMap<String, Integer>();
        bounds.put("maxWords", maxWords);
        bounds.put("minWords", minWords);
        this.wordBounds = Collections.unmodifiableMap(bounds);
        this.minSlots = minSlots;
        this.maxSlots = maxSlots;
        this.descriptionVi = descriptionVi;
        this.status = status;
    }

    static ConversationLevelProfile require(String level) {
        if (level == null || !SUPPORTED_LEVELS.contains(level)) throw new IllegalArgumentException("Unsupported conversation level");
        return valueOf(level);
    }

    static ConversationLevelProfile requireShadow(String level) {
        if (level == null || !List.of("B2", "C1").contains(level)) throw new IllegalArgumentException("Unsupported shadow level");
        return valueOf(level);
    }

    Status status() { return status; }
    Map<String, Integer> wordBounds() {
        if (status == Status.PLANNED) throw new IllegalStateException("Planned level has no generation bounds");
        return wordBounds;
    }
    int minSlots() { return minSlots; }
    int maxSlots() { return maxSlots; }
    String descriptionVi() { return descriptionVi; }

    List<String> taskTypes() {
        if (this == C2) return List.of("implication", "tone", "allusion", "humour", "sociocultural_meaning", "fine_grained_stance");
        if (this == A1) return List.of("direct_detail");
        var types = new java.util.ArrayList<>(List.of("direct_detail", "gist", "reason_purpose",
                "change_revision", "instruction_sequence", "condition"));
        if (List.of(B1, B2, C1).contains(this)) types.addAll(List.of("opinion_attitude", "inference_prediction"));
        if (this == B2 || this == C1) types.add("argument_relation");
        if (this == C1) types.add("implicit_intention");
        return List.copyOf(types);
    }

    Map<String, String> skills() {
        if (this == C2) throw new IllegalStateException("Planned C2 skills require a separate validated rubric");
        var skills = new LinkedHashMap<String, String>();
        for (String key : List.of("condition", "change", "gist", "reason", "instructions", "details"))
            skills.put(key, ConversationContent.SKILLS.get(key));
        if (List.of(B1, B2, C1).contains(this)) for (String key : List.of("attitude", "inference"))
            skills.put(key, ConversationContent.SKILLS.get(key));
        if (this == B2 || this == C1) skills.put("argument", "Theo dõi lập luận và quan hệ ý");
        if (this == C1) skills.put("intention", "Diễn giải ý định và lập trường ngầm");
        return Collections.unmodifiableMap(skills);
    }
}
