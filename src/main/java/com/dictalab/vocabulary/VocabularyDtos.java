package com.dictalab.vocabulary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class VocabularyDtos {
    private VocabularyDtos() {}
    public record DeckEdit(@NotBlank @Size(max=120) String title, @NotNull @Size(max=1000) String description,
                           @Pattern(regexp="A1|A2|B1|B2") @NotNull String level, @Min(0) long revision) {}
    public record NoteEdit(@NotBlank @Size(max=200) String term, @NotBlank @Size(max=1000) String meaningVi,
                           @NotNull @Size(max=40) String partOfSpeech, @NotNull @Size(max=1000) String exampleEn,
                           @NotNull @Size(max=1000) String exampleVi, @NotNull @Size(max=500) String tags,
                           @NotNull @Size(max=20) List<@NotBlank @Size(max=200) String> alternatives,
                           @Min(0) long revision, boolean meaningRecall) {}
    public record SourceNote(@NotNull @Positive Long wordAttemptId, String existingNoteId, @NotNull @Valid NoteEdit note) {}
    public record SessionStart(@Pattern(regexp="SRS|PRACTICE|CONTEXT_TRANSLATION") @NotNull String mode, String deckId) {}
    public record Answer(@NotNull @Size(max=4000) String answer) {}
    public record TargetResult(@NotBlank String noteId, @Pattern(regexp="REMEMBERED|UNSURE|FORGOTTEN") @NotNull String result) {}
    public record GradeRequest(@NotNull ReviewScheduler.Grade grade,
                               @NotNull @Size(max=3) List<@NotNull @Valid TargetResult> targets) {}
    public record Suspension(boolean suspended, @Min(0) long revision) {}
    public record Publication(boolean published, @Min(0) long revision) {}
    public record ImportCsv(@NotBlank String deckId, @NotNull @Size(max=2097152) String csv) {}
    public record ContextTarget(@NotBlank String noteId, @NotBlank @Size(max=200) String surfaceForm) {}
    public record ContextEdit(@NotBlank @Size(max=1000) String prompt,
                              @NotBlank @Size(max=1000) String translationVi,
                              @Pattern(regexp="A1|A2|B1|B2") @NotNull String level,
                              @NotNull @Size(max=10) List<@NotBlank @Size(max=1000) String> alternatives,
                              @NotNull @Size(min=2,max=3) List<@NotNull @Valid ContextTarget> targets,
                              boolean published, @Min(0) long revision) {}
    public record Item(String id, int position, String kind, String prompt, String answer, String evaluation,
                       boolean assisted, Instant gradedAt, Map<String,Object> feedback, Map<String,Object> result) {}
    public record Session(String id, String mode, String status, Instant expiresAt, List<Item> items, String message) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasMore) {}
}
