package com.dictalab.vocabulary;

import java.time.Instant;

public interface ReviewScheduler {
    enum Grade { AGAIN, HARD, GOOD, EASY }
    record State(String state, String version, int intervalDays, double ease, int repetitions,
                 int lapses, Instant dueAt) {}
    State next(State previous, Grade grade, Instant now);
}
