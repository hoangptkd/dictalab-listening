package com.dictalab.vocabulary;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Component
public class RuleAnswerEvaluator implements AnswerEvaluator {
    public static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replace('’','\'').strip()
                .replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    @Override public String evaluate(String answer,List<String> accepted,boolean strict) {
        boolean correct=accepted.stream().map(RuleAnswerEvaluator::normalize).anyMatch(normalize(answer)::equals);
        return correct ? "CORRECT" : strict ? "INCORRECT" : "UNDETERMINED";
    }
}
