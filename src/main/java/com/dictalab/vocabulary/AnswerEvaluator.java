package com.dictalab.vocabulary;

import java.util.List;

public interface AnswerEvaluator {
    String evaluate(String answer, List<String> accepted, boolean strict);
}
