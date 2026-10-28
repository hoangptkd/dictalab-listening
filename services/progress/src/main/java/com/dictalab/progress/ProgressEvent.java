package com.dictalab.progress;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;

public record ProgressEvent(
        @Min(1) @Max(1) int schemaVersion,
        @NotNull java.util.UUID eventId,
        @NotNull @Pattern(regexp="PracticeCompleted\\.v1|UserDeleted\\.v1") String type,
        @Positive long userId,
        @NotNull Instant occurredAt,
        @Valid Session session) {

    @AssertTrue
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPayloadConsistent() {
        return "PracticeCompleted.v1".equals(type) ? session != null : session == null;
    }

    public record Session(
            @NotNull java.util.UUID sessionId,
            @PositiveOrZero long revision,
            @Positive long lessonId,
            @NotBlank @Size(max=500) String lessonTitle,
            @NotNull @Pattern(regexp="A1|A2|B1|B2") String level,
            @NotNull Instant completedAt,
            @NotNull LocalDate completedDate,
            @PositiveOrZero int practiceSeconds,
            @DecimalMin("0") @DecimalMax("100") double firstAccuracy,
            @DecimalMin("0") @DecimalMax("100") double finalAccuracy,
            @DecimalMin("0") @DecimalMax("100") double score,
            @PositiveOrZero int knownMissed,
            @PositiveOrZero int unknownWords) {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isFinite() {
            return Double.isFinite(firstAccuracy) && Double.isFinite(finalAccuracy) && Double.isFinite(score);
        }
    }
}
