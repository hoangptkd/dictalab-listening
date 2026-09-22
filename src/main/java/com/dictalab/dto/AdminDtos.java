package com.dictalab.dto;

import com.dictalab.domain.CefrLevel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateLessonRequest(
            @NotBlank(message = "Vui lòng nhập mã bài.")
            @Size(max = 80, message = "Mã bài tối đa 80 ký tự.")
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                    message = "Mã bài chỉ gồm chữ thường, số và dấu gạch ngang.")
            String slug,
            @NotBlank(message = "Vui lòng nhập tên bài.") @Size(max = 140) String title,
            @NotBlank(message = "Vui lòng nhập mô tả.") @Size(max = 500) String description,
            @NotNull(message = "Vui lòng chọn level.") CefrLevel level,
            @NotBlank(message = "Vui lòng nhập chủ đề.") @Size(max = 60) String topic,
            @Min(value = 1, message = "Thời lượng phải từ 1 phút.")
            @Max(value = 120, message = "Thời lượng tối đa 120 phút.") int estimatedMinutes,
            @Min(value = 1, message = "Thứ tự hiển thị phải từ 1.") int displayOrder,
            @Min(0) @Max(10) Integer speakerCount,
            @Size(max = 120) String accentProfile,
            @Min(1) @Max(5) Integer vocabularyDifficulty,
            @Min(1) @Max(5) Integer grammarDifficulty,
            @Min(1) @Max(5) Integer sentenceDifficulty
    ) {
    }

    public record UpdateLessonRequest(
            @NotBlank(message = "Vui lòng nhập tên bài.") @Size(max = 140) String title,
            @NotBlank(message = "Vui lòng nhập mô tả.") @Size(max = 500) String description,
            @NotNull(message = "Vui lòng chọn level.") CefrLevel level,
            @NotBlank(message = "Vui lòng nhập chủ đề.") @Size(max = 60) String topic,
            @Min(value = 1, message = "Thời lượng phải từ 1 phút.") @Max(120) int estimatedMinutes,
            @Min(value = 1, message = "Thứ tự hiển thị phải từ 1.") int displayOrder,
            @Min(0) @Max(10) Integer speakerCount,
            @Size(max = 120) String accentProfile,
            @Min(1) @Max(5) Integer vocabularyDifficulty,
            @Min(1) @Max(5) Integer grammarDifficulty,
            @Min(1) @Max(5) Integer sentenceDifficulty
    ) {
    }

    public record SentenceRequest(
            @NotBlank(message = "Transcript không được để trống.")
            @Size(max = 1000, message = "Mỗi câu tối đa 1000 ký tự.") String transcript,
            @DecimalMin(value = "0.5", message = "Tốc độ đọc tối thiểu là 0.5.")
            @DecimalMax(value = "2.0", message = "Tốc độ đọc tối đa là 2.0.") double speechRate,
            @DecimalMin(value = "0.0", message = "Mốc bắt đầu không được âm.") Double audioStartSeconds,
            @DecimalMin(value = "0.01", message = "Mốc kết thúc phải lớn hơn 0.") Double audioEndSeconds
    ) {
    }

    public record ReorderSentencesRequest(
            @NotEmpty(message = "Danh sách thứ tự câu không được trống.") List<@NotNull Long> sentenceIds
    ) {
    }

    public record PublicationRequest(boolean published) {
    }

    public record SentenceResponse(
            Long id,
            int position,
            String transcript,
            String audioUrl,
            Double audioStartSeconds,
            Double audioEndSeconds,
            double speechRate
    ) {
    }

    public record LessonListItem(
            Long id,
            String slug,
            String title,
            CefrLevel level,
            String topic,
            int displayOrder,
            boolean published,
            String contentOrigin,
            boolean contentLocked,
            int sentenceCount,
            Instant updatedAt
    ) {
    }

    public record LessonResponse(
            Long id,
            String slug,
            String title,
            String description,
            CefrLevel level,
            String topic,
            int estimatedMinutes,
            int displayOrder,
            boolean published,
            String contentOrigin,
            boolean contentLocked,
            int speakerCount,
            String accentProfile,
            int vocabularyDifficulty,
            int grammarDifficulty,
            int sentenceDifficulty,
            boolean assessmentCalibrated,
            Instant updatedAt,
            List<SentenceResponse> sentences
    ) {
    }
}
