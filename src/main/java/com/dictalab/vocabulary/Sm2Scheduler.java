package com.dictalab.vocabulary;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public final class Sm2Scheduler implements ReviewScheduler {
    public static final String VERSION = "SM2_PRODUCT_V1";
    @Override public State next(State old, Grade grade, Instant now) {
        if (!VERSION.equals(old.version())) throw new IllegalArgumentException("Unsupported scheduler version");
        int q = switch (grade) { case AGAIN -> 1; case HARD -> 3; case GOOD -> 4; case EASY -> 5; };
        double ease = Math.max(1.3, old.ease() + .1 - (5-q) * (.08 + (5-q) * .02));
        if (grade == Grade.AGAIN)
            return new State("RELEARNING", VERSION, 0, ease, 0, old.lapses()+1, now.plusSeconds(600));
        int days = old.repetitions() == 0 ? 1 : old.repetitions() == 1 ? 6
                : (int)Math.min(365, Math.ceil(old.intervalDays() * old.ease()));
        return new State("REVIEW", VERSION, days, ease, old.repetitions()+1, old.lapses(), now.plus(days, ChronoUnit.DAYS));
    }
}
