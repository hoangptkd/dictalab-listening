package com.dictalab.service;

import com.dictalab.domain.LearnerErrorType;
import com.dictalab.domain.ListeningErrorCategory;
import com.dictalab.domain.WordLearningOutcome;
import com.dictalab.dto.ApiDtos;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CoachAnalysis {
    private final TranscriptTokenizer tokenizer;
    private static final Set<String> NUMBERS = Set.of("zero", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety", "hundred", "thousand", "million", "first", "second", "third");

    public CoachAnalysis(TranscriptTokenizer tokenizer) { this.tokenizer = tokenizer; }

    public record ReviewItem(String id, long sentenceId, int position, int wordIndex, String heard,
                             String expected, String transcript, String audioUrl, Double audioStartSeconds,
                             Double audioEndSeconds, String pattern, String label, String explanation,
                             int priority, int occurrences) {}
    public record Weakness(String pattern, String title, String insight, List<String> evidenceIds) {}
    public record Action(String pattern, String instruction) {}
    public record Progress(String status, String text, Double currentAccuracy, Double baselineAccuracy,
                           Double deltaPoints, Integer comparedLessonCount, String level, String comparisonReason) {
        public Progress(String status, String text) { this(status, text, null, null, null, null, null, null); }
    }
    public record Generation(String provider, String model, String promptVersion, String schemaVersion) {}
    public record Report(String source, boolean aiEnabled, List<Weakness> weaknesses, Progress progressInsight,
                         List<Action> nextActions, List<ReviewItem> reviewItems,
                         List<ReviewItem> quickReviewItems, Generation generation) {
        public Report {
            if (quickReviewItems == null) quickReviewItems = quickReview(reviewItems);
        }
        public Report(String source, boolean enabled, List<Weakness> weaknesses, Progress progress,
                      List<Action> actions, List<ReviewItem> review) {
            this(source, enabled, weaknesses, progress, actions, review, null, null);
        }
        public Report withGeneration(Generation value) {
            return new Report(source, aiEnabled, weaknesses, progressInsight, nextActions, reviewItems, quickReviewItems, value);
        }
    }

    public static List<ReviewItem> quickReview(List<ReviewItem> items) {
        Set<Long> sentences = new HashSet<>();
        return items.stream().filter(item -> item.audioUrl() != null && !item.audioUrl().isBlank())
                .filter(item -> sentences.add(item.sentenceId())).limit(3).toList();
    }

    public List<ReviewItem> candidates(ApiDtos.LessonResult result, Set<String> properNames,
                                       Map<String, Integer> earlierMistakes) {
        if (result.review() == null) return List.of();
        List<ReviewItem> items = new ArrayList<>();
        Map<String, Integer> repeats = new HashMap<>(earlierMistakes);
        result.review().sentences().forEach(sentence -> sentence.words().stream().filter(this::missed)
                .forEach(word -> repeats.merge(tokenizer.normalize(word.expectedWord()), 1, Integer::sum)));
        for (ApiDtos.SentenceReview sentence : result.review().sentences()) {
            for (ApiDtos.WordReview word : sentence.words()) {
                if (!missed(word)) continue;
                String expected = tokenizer.normalize(word.expectedWord());
                String heard = tokenizer.normalize(word.firstAnswer());
                String pattern;
                int priority;
                ListeningErrorCategory category = word.attempts() == null || word.attempts().isEmpty()
                        ? word.inferredErrorCategory() : word.attempts().get(0).errorCategory();
                boolean ending = !heard.isEmpty() && (expected.equals(heard + "s") || expected.equals(heard + "es")
                        || expected.equals(heard + "ed") || expected.equals(heard + "d"));
                if (isProperName(sentence, word.index(), properNames)) { pattern = "PROPER_NAME"; priority = 5; }
                else if (expected.replace("'", "").equals(heard.replace("'", ""))) { pattern = "SPELLING"; priority = 10; }
                else if (ending) { pattern = "FINAL_SOUNDS"; priority = 80; }
                else if (category == ListeningErrorCategory.SPELLING) { pattern = "SPELLING"; priority = 10; }
                else if (expected.contains("'")) { pattern = "CONTRACTIONS"; priority = 85; }
                else if (expected.matches(".*\\d.*") || Arrays.stream(expected.split("-")).anyMatch(NUMBERS::contains)) {
                    pattern = "NUMBERS"; priority = 80;
                } else if (tokenizer.isFunctionWord(expected)) { pattern = "WEAK_FORMS"; priority = 75; }
                else if (word.errorType() == LearnerErrorType.KNOWN_BUT_MISSED) { pattern = "KNOWN_MISSED"; priority = 90; }
                else if (word.errorType() == LearnerErrorType.UNKNOWN) { pattern = "VOCABULARY"; priority = 45; }
                else { pattern = "WORD_RECOGNITION"; priority = 50; }
                int occurrences = repeats.getOrDefault(expected, 1);
                if (!Set.of("SPELLING", "PROPER_NAME").contains(pattern)) {
                    priority += Math.min(30, (occurrences - 1) * 10);
                    if (word.errorType() == LearnerErrorType.KNOWN_BUT_MISSED) priority += 15;
                }
                items.add(new ReviewItem(sentence.sentenceId() + ":" + word.index(), sentence.sentenceId(),
                        sentence.position(), word.index(), word.firstAnswer() == null ? "" : word.firstAnswer(),
                        word.expectedWord(), sentence.transcript(), sentence.audioUrl(), sentence.audioStartSeconds(),
                        sentence.audioEndSeconds(), pattern, label(pattern), explanation(pattern), priority, occurrences));
            }
        }
        return items.stream().sorted(Comparator.comparingInt(ReviewItem::priority).reversed()
                .thenComparingInt(ReviewItem::position).thenComparingInt(ReviewItem::wordIndex)).toList();
    }

    private boolean missed(ApiDtos.WordReview word) {
        // Missing legacy telemetry and prefilled proper names are not evidence of a listening error.
        return word.outcome() != null && word.outcome() != WordLearningOutcome.FIRST_ATTEMPT
                && !tokenizer.normalize(word.expectedWord()).equals(tokenizer.normalize(word.firstAnswer()));
    }

    private boolean isProperName(ApiDtos.SentenceReview sentence, int index, Set<String> names) {
        List<String> words = sentence.words().stream().map(w -> tokenizer.normalize(w.expectedWord())).toList();
        for (String name : names) {
            List<String> tokens = tokenizer.words(name).stream().map(tokenizer::normalize).toList();
            for (int start = Math.max(0, index - tokens.size() + 1); start <= index && start + tokens.size() <= words.size(); start++) {
                if (!tokens.isEmpty() && words.subList(start, start + tokens.size()).equals(tokens)) return true;
            }
        }
        return false;
    }

    public Progress progress(double accuracy, List<Double> previous, boolean comparable) {
        return progress(accuracy, previous, comparable, false, null);
    }

    public Progress progress(double accuracy, List<Double> previous, boolean firstExposure, boolean shared, String level) {
        if (shared || !firstExposure) return new Progress("NOT_COMPARABLE",
                shared ? "Buổi học cùng bạn" : "Kết quả luyện lại", accuracy, null, null, 0, level,
                shared ? "SHARED" : "REPEAT");
        if (previous.size() < 2) return new Progress("INSUFFICIENT",
                "Chưa đủ dữ liệu so sánh", accuracy, null, null, previous.size(), level, "NEW");
        double baseline = previous.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double delta = Math.round((accuracy - baseline) * 10) / 10.0;
        String direction = delta >= 3 ? "UP" : delta <= -3 ? "DOWN" : "STEADY";
        String text = switch (direction) {
            case "UP" -> "Độ đúng lần đầu cao hơn trung bình " + previous.size() + " bài mới cùng cấp độ trước đó " + Math.abs(delta) + " điểm %.";
            case "DOWN" -> "Độ đúng lần đầu thấp hơn trung bình " + previous.size() + " bài mới cùng cấp độ trước đó " + Math.abs(delta) + " điểm %.";
            default -> "Độ đúng lần đầu đang ổn định so với " + previous.size() + " bài mới cùng cấp độ trước đó.";
        };
        return new Progress(direction, text + " Độ khó từng bài có thể khác nhau.", accuracy,
                baseline, delta, previous.size(), level, "NEW");
    }

    /** Local schema is independent of the legacy cloud report contract. */
    public Report interpretLocal(JsonNode ai, List<ReviewItem> candidates, Progress progress) {
        require(ai.isObject() && ai.size() == 2 && ai.has("focus") && ai.has("reviewIds"), "local object");
        Map<String, ReviewItem> byId = candidates.stream().collect(Collectors.toMap(ReviewItem::id, x -> x));
        JsonNode ids = ai.path("reviewIds"), focus = ai.path("focus");
        require(ids.isArray() && ids.size() <= 3, "local review limit");
        List<String> preferred = new ArrayList<>();
        for (JsonNode id : ids) {
            require(id.isTextual() && byId.containsKey(id.asText()) && !preferred.contains(id.asText()), "local review evidence");
            preferred.add(id.asText());
        }
        List<Weakness> weaknesses = new ArrayList<>();
        if (!focus.isNull()) {
            require(focus.isObject() && focus.size() == 3 && focus.path("pattern").isTextual(), "local focus");
            String pattern = focus.path("pattern").asText();
            JsonNode evidence = focus.path("evidenceIds"), insight = focus.path("insight");
            require(evidence.isArray() && evidence.size() >= 1 && evidence.size() <= 2, "local evidence count");
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode id : evidence) {
                require(id.isTextual() && byId.containsKey(id.asText()) && byId.get(id.asText()).pattern().equals(pattern)
                        && !evidenceIds.contains(id.asText()), "local evidence pattern");
                evidenceIds.add(id.asText());
            }
            require(insight.isTextual(), "local text type");
            String text = insight.asText();
            require(!text.isBlank() && text.codePointCount(0, text.length()) <= 120
                    && text.codePoints().noneMatch(c -> Character.isISOControl(c) || c == '<' || c == '>'
                            || c == 0x2028 || c == 0x2029)
                    && text.matches("(?s).*[ăâđêôơưĂÂĐÊÔƠƯàáèéìíòóùúýÀÁÈÉÌÍÒÓÙÚÝ\u1ea0-\u1ef9].*")
                    && !text.matches("(?s).*\\p{L}[.!?]\\s+\\p{Lu}.*"), "local one Vietnamese sentence");
            // Sparse typed evidence cannot confirm an acoustic cause or override the learner's own classification.
            if (Set.of("WEAK_FORMS", "FINAL_SOUNDS", "VOCABULARY", "SPELLING", "PROPER_NAME", "WORD_RECOGNITION")
                    .contains(pattern)) text = explanation(pattern);
            weaknesses.add(new Weakness(pattern, label(pattern), text.trim(), evidenceIds));
            preferred.addAll(0, evidenceIds.stream().filter(id -> !preferred.contains(id)).toList());
        }
        List<ReviewItem> review = selectReview(candidates, preferred);
        Set<String> visible = review.stream().map(ReviewItem::id).collect(Collectors.toSet());
        require(weaknesses.stream().flatMap(w -> w.evidenceIds().stream()).allMatch(visible::contains), "local hidden evidence");
        return new Report("AI", true, List.copyOf(weaknesses), progress, List.of(), review);
    }

    public Report baseline(List<ReviewItem> candidates, Progress progress, boolean enabled, String source) {
        List<ReviewItem> review = selectReview(candidates, List.of());
        Map<String, List<ReviewItem>> groups = review.stream().collect(Collectors.groupingBy(
                ReviewItem::pattern, LinkedHashMap::new, Collectors.toList()));
        List<Weakness> weaknesses = groups.entrySet().stream().limit(3).map(entry -> new Weakness(entry.getKey(),
                label(entry.getKey()), explanation(entry.getKey()), entry.getValue().stream().limit(2).map(ReviewItem::id).toList())).toList();
        List<Action> actions = weaknesses.stream().map(w -> new Action(w.pattern(), exercise(w.pattern()))).toList();
        return new Report(source, enabled, weaknesses, progress, actions, review);
    }

    public Report interpret(JsonNode ai, List<ReviewItem> candidates, Progress progress) {
        require(ai.isObject() && ai.size() == 4, "object");
        JsonNode weaknesses = ai.path("weaknesses"), actions = ai.path("nextActions"), ids = ai.path("reviewIds");
        require(weaknesses.isArray() && weaknesses.size() <= 3 && actions.isArray() && actions.size() <= 3, "limits");
        require(ids.isArray() && ids.size() >= Math.min(8, candidates.size()) && ids.size() <= 12, "review limit");
        require(ai.path("progressInsight").path("status").asText().equals(progress.status())
                && ai.path("progressInsight").path("text").asText().equals(progress.text()), "progress");
        Map<String, ReviewItem> byId = candidates.stream().collect(Collectors.toMap(ReviewItem::id, x -> x));
        List<String> chosen = new ArrayList<>();
        for (JsonNode id : ids) {
            require(id.isTextual() && byId.containsKey(id.asText()) && !chosen.contains(id.asText()), "review evidence");
            chosen.add(id.asText());
        }
        List<Weakness> parsedWeaknesses = new ArrayList<>();
        Set<String> patterns = new HashSet<>();
        for (JsonNode weakness : weaknesses) {
            String pattern = weakness.path("pattern").asText();
            require(patterns.add(pattern), "duplicate pattern");
            JsonNode evidence = weakness.path("evidenceIds");
            require(evidence.isArray() && !evidence.isEmpty() && evidence.size() <= 2, "evidence count");
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode id : evidence) {
                ReviewItem item = byId.get(id.asText());
                require(item != null && item.pattern().equals(pattern) && !evidenceIds.contains(id.asText()), "evidence pattern");
                evidenceIds.add(id.asText());
                if (!chosen.contains(id.asText())) chosen.add(0, id.asText());
            }
            String insight = shortText(weakness.path("insight"));
            // Keep acoustic hypotheses explicit regardless of model phrasing.
            if (Set.of("WEAK_FORMS", "FINAL_SOUNDS").contains(pattern)) insight = explanation(pattern);
            parsedWeaknesses.add(new Weakness(pattern, label(pattern), insight, evidenceIds));
        }
        require(candidates.isEmpty() || !parsedWeaknesses.isEmpty(), "missing weaknesses");
        List<Action> parsedActions = new ArrayList<>();
        Set<String> actionPatterns = new HashSet<>();
        for (JsonNode action : actions) {
            String pattern = action.path("pattern").asText();
            require(patterns.contains(pattern) && actionPatterns.add(pattern), "action pattern");
            parsedActions.add(new Action(pattern, shortText(action.path("instruction"))));
        }
        require(parsedWeaknesses.isEmpty() || !parsedActions.isEmpty(), "missing action");
        List<ReviewItem> review = selectReview(candidates, chosen);
        Set<String> visible = review.stream().map(ReviewItem::id).collect(Collectors.toSet());
        require(parsedWeaknesses.stream().flatMap(w -> w.evidenceIds().stream()).allMatch(visible::contains), "hidden evidence");
        return new Report("AI", true, parsedWeaknesses, progress, parsedActions, review);
    }

    private List<ReviewItem> selectReview(List<ReviewItem> candidates, List<String> preferred) {
        // ponytail: cap at two errors per sentence for variety; fill remaining slots if fewer sentences exist.
        Comparator<ReviewItem> ordering = Comparator.comparingInt((ReviewItem item) -> item.priority() >= 45 ? 0 : 1)
                .thenComparingInt(item -> preferred.contains(item.id()) ? preferred.indexOf(item.id()) : 1000)
                .thenComparing(Comparator.comparingInt(ReviewItem::priority).reversed());
        List<ReviewItem> ranked = candidates.stream().sorted(ordering).toList();
        List<ReviewItem> selected = new ArrayList<>();
        Map<Long, Integer> perSentence = new HashMap<>();
        for (boolean listening : List.of(true, false)) {
            List<ReviewItem> tier = ranked.stream().filter(item -> (item.priority() >= 45) == listening).toList();
            for (ReviewItem item : tier) {
                if (selected.size() == 12) break;
                if (perSentence.getOrDefault(item.sentenceId(), 0) >= 2) continue;
                selected.add(item);
                perSentence.merge(item.sentenceId(), 1, Integer::sum);
            }
            for (ReviewItem item : tier) {
                if (selected.size() == 12) break;
                if (!selected.contains(item)) selected.add(item);
            }
        }
        return List.copyOf(selected);
    }

    private String shortText(JsonNode value) {
        require(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 160
                && !value.asText().contains("\n"), "text length");
        return value.asText().trim();
    }

    private void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException("Invalid coach JSON: " + reason);
    }

    private String label(String pattern) {
        return switch (pattern) {
            case "CONTRACTIONS" -> "Dạng rút gọn";
            case "NUMBERS" -> "Số và thứ tự";
            case "FINAL_SOUNDS" -> "Âm cuối cần kiểm tra";
            case "WEAK_FORMS" -> "Từ nhẹ âm cần kiểm tra";
            case "KNOWN_MISSED" -> "Từ quen nhưng nghe lỡ";
            case "VOCABULARY" -> "Từ chưa quen";
            case "SPELLING" -> "Chính tả / gõ nhầm";
            case "PROPER_NAME" -> "Tên riêng";
            default -> "Nhận diện từ trong câu";
        };
    }

    private String explanation(String pattern) {
        return switch (pattern) {
            case "CONTRACTIONS" -> "Bạn chưa nhận đúng dạng rút gọn trong lần nhập đầu.";
            case "NUMBERS" -> "Số hoặc từ chỉ thứ tự chưa khớp với câu gốc.";
            case "FINAL_SOUNDS" -> "Có thể bỏ lỡ đuôi từ; nghe lại để phân biệt với lỗi chính tả hoặc ngữ pháp.";
            case "WEAK_FORMS" -> "Có thể bỏ lỡ từ ít nhấn; cần nghe lại để kiểm tra, chưa đủ dữ liệu kết luận cách phát âm.";
            case "KNOWN_MISSED" -> "Bạn đã đánh dấu biết từ này nhưng chưa nhận ra khi nghe.";
            case "VOCABULARY" -> "Bạn đã đánh dấu đây là từ chưa biết.";
            case "SPELLING" -> "Đáp án gần đúng về chữ; ưu tiên thấp hơn lỗi nhận âm.";
            case "PROPER_NAME" -> "Đây là tên riêng; không dùng làm trọng tâm đánh giá nghe.";
            default -> "Đối chiếu lần nhập đầu với từ đúng trong ngữ cảnh câu.";
        };
    }

    private String exercise(String pattern) {
        return switch (pattern) {
            case "CONTRACTIONS" -> "Nghe lại 3 câu có dạng rút gọn, đọc nối theo rồi gõ lại từ còn thiếu.";
            case "FINAL_SOUNDS" -> "Nghe cuối từ ở tốc độ thường, đọc theo cả cụm rồi gõ lại đuôi từ.";
            case "WEAK_FORMS" -> "Nghe cả cụm, chú ý từ ngắn giữa hai từ nhấn rồi điền lại.";
            case "NUMBERS" -> "Nghe lại số trong câu, nhắc lại thành tiếng rồi gõ đúng từ chỉ số.";
            case "VOCABULARY" -> "Xem nghĩa từ trong ngữ cảnh, nghe lại rồi điền từ khi đáp án đã ẩn.";
            case "SPELLING", "PROPER_NAME" -> "Đọc kỹ chữ trong từ, nghe lại rồi gõ lại một lần.";
            default -> "Nghe lại 3 câu có từ đã lỡ, đọc theo cả cụm rồi điền từ khi đáp án đã ẩn.";
        };
    }
}
