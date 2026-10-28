package com.dictalab.vocabulary;

import java.util.List;
import java.util.Map;

public interface ContextExerciseProvider {
    record Exercise(String id,String prompt,List<String> translations,List<Map<String,Object>> targets,String source) {}
    List<Exercise> select(long user,String deckId,int limit);
}
