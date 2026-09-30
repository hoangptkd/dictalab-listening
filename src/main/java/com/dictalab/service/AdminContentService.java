package com.dictalab.service;

import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.dto.AdminDtos;
import com.dictalab.exception.ContentManagementException;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class AdminContentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminContentService.class);

    private final LessonRepository lessonRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final PracticeSessionRepository sessionRepository;
    private final MediaStorageService mediaStorage;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock = Clock.systemUTC();

    public AdminContentService(LessonRepository lessonRepository,
                               LessonSentenceRepository sentenceRepository,
                               PracticeSessionRepository sessionRepository,
                               MediaStorageService mediaStorage,
                               JdbcTemplate jdbcTemplate) {
        this.lessonRepository = lessonRepository;
        this.sentenceRepository = sentenceRepository;
        this.sessionRepository = sessionRepository;
        this.mediaStorage = mediaStorage;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AdminDtos.LessonListItem> listLessons() {
        return lessonRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(lesson -> new AdminDtos.LessonListItem(
                        lesson.getId(), lesson.getSlug(), lesson.getTitle(), lesson.getLevel(), lesson.getTopic(),
                        lesson.getDisplayOrder(), lesson.isActive(), lesson.getContentOrigin(),
                        contentLocked(lesson.getId()),
                        Math.toIntExact(sentenceRepository.countByLessonId(lesson.getId())), lesson.getUpdatedAt()
                )).toList();
    }

    public AdminDtos.LessonResponse lesson(Long lessonId) {
        return toResponse(requireLesson(lessonId));
    }
    @Transactional
    public AdminDtos.LessonResponse copyLesson(Long lessonId) {
        Lesson original=requireLesson(lessonId);
        if(original.isPersonalContent()) throw new ContentManagementException("Không sao chép nội dung cá nhân thành bài phát hành công khai.");
        Instant now=Instant.now(clock);
        String slug="copy-"+lessonId+"-"+java.util.UUID.randomUUID().toString().substring(0,8);
        String title=original.getTitle().substring(0,Math.min(125,original.getTitle().length()))+" · Bản sửa";
        Lesson copied=lessonRepository.save(new Lesson(slug,title,original.getDescription(),original.getLevel(),original.getTopic(),original.getEstimatedMinutes(),original.getDisplayOrder()+1,now));
        copied.updateAssessmentMetadata(original.getSpeakerCount(),original.getAccentProfile(),original.getVocabularyDifficulty(),original.getGrammarDifficulty(),original.getSentenceDifficulty(),false,now);
        for(LessonSentence source:sentenceRepository.findByLessonIdOrderByPositionAsc(lessonId)) {
            LessonSentence sentence=new LessonSentence(copied,source.getPosition(),source.getTranscript(),source.getSpeechRate());
            sentence.update(source.getTranscript(),source.getSpeechRate(),source.getAudioStartSeconds(),source.getAudioEndSeconds());
            // Keep audio identity for exposure tracking; uploads always create a new file.
            if(source.getAudioUrl()!=null) sentence.attachAudio(source.getAudioUrl());
            sentenceRepository.save(sentence);
        }
        sentenceRepository.flush(); return toResponse(copied);
    }

    @Transactional
    public AdminDtos.LessonResponse createLesson(AdminDtos.CreateLessonRequest request) {
        String slug = request.slug().trim();
        if (lessonRepository.existsBySlug(slug)) {
            throw new ContentManagementException("Mã bài đã tồn tại. Hãy chọn mã khác.");
        }
        Instant now = Instant.now(clock);
        Lesson lesson = new Lesson(
                slug, request.title().trim(), request.description().trim(), request.level(), request.topic().trim(),
                request.estimatedMinutes(), request.displayOrder(), now
        );
        int defaultDifficulty = defaultDifficulty(request.level());
        lesson.updateAssessmentMetadata(
                request.speakerCount() == null ? 0 : request.speakerCount(), request.accentProfile(),
                request.vocabularyDifficulty() == null ? defaultDifficulty : request.vocabularyDifficulty(),
                request.grammarDifficulty() == null ? defaultDifficulty : request.grammarDifficulty(),
                request.sentenceDifficulty() == null ? defaultDifficulty : request.sentenceDifficulty(),
                false, now
        );
        return toResponse(lessonRepository.save(lesson));
    }

    @Transactional
    public AdminDtos.LessonResponse updateLesson(Long lessonId, AdminDtos.UpdateLessonRequest request) {
        Lesson lesson = requireLesson(lessonId);
        boolean levelChanged = lesson.getLevel() != request.level();
        Integer listeningUnits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM listening_units WHERE lesson_id=?", Integer.class, lessonId);
        if ((sessionRepository.existsByLessonId(lessonId) || listeningUnits != null && listeningUnits > 0)
                && lesson.getLevel() != request.level()) {
            throw new ContentManagementException(
                    "Không thể đổi level vì bài đã có lịch sử học. Các thông tin mô tả khác vẫn có thể sửa."
            );
        }
        lesson.updateMetadata(
                request.title().trim(), request.description().trim(), request.level(), request.topic().trim(),
                request.estimatedMinutes(), request.displayOrder(), Instant.now(clock)
        );
        int fallbackDifficulty = levelChanged ? defaultDifficulty(request.level()) : lesson.getVocabularyDifficulty();
        int speakerCount = request.speakerCount() == null ? lesson.getSpeakerCount() : request.speakerCount();
        String accentProfile = request.accentProfile() == null ? lesson.getAccentProfile() : request.accentProfile();
        int vocabularyDifficulty = request.vocabularyDifficulty() == null
                ? fallbackDifficulty : request.vocabularyDifficulty();
        int grammarDifficulty = request.grammarDifficulty() == null
                ? (levelChanged ? defaultDifficulty(request.level()) : lesson.getGrammarDifficulty())
                : request.grammarDifficulty();
        int sentenceDifficulty = request.sentenceDifficulty() == null
                ? (levelChanged ? defaultDifficulty(request.level()) : lesson.getSentenceDifficulty())
                : request.sentenceDifficulty();
        boolean assessmentChanged = levelChanged
                || speakerCount != lesson.getSpeakerCount()
                || !normalizedAccent(accentProfile).equals(normalizedAccent(lesson.getAccentProfile()))
                || vocabularyDifficulty != lesson.getVocabularyDifficulty()
                || grammarDifficulty != lesson.getGrammarDifficulty()
                || sentenceDifficulty != lesson.getSentenceDifficulty();
        lesson.updateAssessmentMetadata(
                speakerCount, accentProfile, vocabularyDifficulty, grammarDifficulty, sentenceDifficulty,
                assessmentChanged ? false : lesson.isAssessmentCalibrated(), Instant.now(clock)
        );
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse addSentence(Long lessonId, AdminDtos.SentenceRequest request) {
        Lesson lesson = requireLesson(lessonId);
        ensureContentEditable(lesson);
        validateAudioSegment(request.audioStartSeconds(), request.audioEndSeconds());
        int position = Math.toIntExact(sentenceRepository.countByLessonId(lessonId)) + 1;
        LessonSentence sentence = new LessonSentence(
                lesson, position, request.transcript().trim(), normalizedRate(request.speechRate())
        );
        sentence.update(request.transcript().trim(), normalizedRate(request.speechRate()),
                request.audioStartSeconds(), request.audioEndSeconds());
        sentenceRepository.save(sentence);
        lesson.setPublished(false, Instant.now(clock));
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse updateSentence(Long lessonId, Long sentenceId,
                                                    AdminDtos.SentenceRequest request) {
        Lesson lesson = requireLesson(lessonId);
        ensureContentEditable(lesson);
        validateAudioSegment(request.audioStartSeconds(), request.audioEndSeconds());
        LessonSentence sentence = requireSentence(lessonId, sentenceId);
        sentence.update(request.transcript().trim(), normalizedRate(request.speechRate()),
                request.audioStartSeconds(), request.audioEndSeconds());
        lesson.setPublished(false, Instant.now(clock));
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse uploadAudio(Long lessonId, Long sentenceId, MultipartFile file) {
        Lesson lesson = requireLesson(lessonId);
        ensureContentEditable(lesson);
        LessonSentence sentence = requireSentence(lessonId, sentenceId);
        MediaStorageService.StoredMedia stored = mediaStorage.storeAudio(file);
        String previousUrl = sentence.getAudioUrl();
        manageFilesAfterTransaction(previousUrl, stored.publicUrl());
        sentence.attachAudio(stored.publicUrl());
        lesson.setPublished(false, Instant.now(clock));
        sentenceRepository.saveAndFlush(sentence);
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse deleteSentence(Long lessonId, Long sentenceId) {
        Lesson lesson = requireLesson(lessonId);
        ensureContentEditable(lesson);
        LessonSentence sentence = requireSentence(lessonId, sentenceId);
        String audioUrl = sentence.getAudioUrl();
        deleteFileAfterCommit(audioUrl);
        sentenceRepository.delete(sentence);
        sentenceRepository.flush();
        List<LessonSentence> remaining = sentenceRepository.findByLessonIdOrderByPositionAsc(lessonId);
        assignPositions(remaining);
        lesson.setPublished(false, Instant.now(clock));
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse reorderSentences(Long lessonId,
                                                     AdminDtos.ReorderSentencesRequest request) {
        Lesson lesson = requireLesson(lessonId);
        ensureContentEditable(lesson);
        List<LessonSentence> sentences = sentenceRepository.findByLessonIdOrderByPositionAsc(lessonId);
        List<Long> requestedIds = request.sentenceIds();
        if (requestedIds.size() != sentences.size()
                || new HashSet<>(requestedIds).size() != requestedIds.size()
                || !new HashSet<>(requestedIds).equals(sentences.stream().map(LessonSentence::getId)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw new ContentManagementException("Danh sách sắp xếp phải chứa đúng tất cả câu của bài.");
        }
        List<LessonSentence> ordered = requestedIds.stream()
                .map(id -> sentences.stream().filter(sentence -> sentence.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        assignPositions(ordered);
        lesson.setPublished(false, Instant.now(clock));
        return toResponse(lesson);
    }

    @Transactional
    public AdminDtos.LessonResponse setPublication(Long lessonId, boolean published) {
        Lesson lesson = requireLesson(lessonId);
        if (published) {
            List<LessonSentence> sentences = sentenceRepository.findByLessonIdOrderByPositionAsc(lessonId);
            if (sentences.isEmpty()) {
                throw new ContentManagementException("Cần thêm ít nhất một câu trước khi xuất bản.");
            }
            if (sentences.stream().anyMatch(sentence -> sentence.getAudioUrl() == null
                    || sentence.getAudioUrl().isBlank())) {
                throw new ContentManagementException("Mỗi câu phải có audio trước khi xuất bản.");
            }
        }
        lesson.setPublished(published, Instant.now(clock));
        return toResponse(lesson);
    }

    private void assignPositions(List<LessonSentence> ordered) {
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).moveTo(-(index + 1));
        }
        sentenceRepository.saveAllAndFlush(ordered);
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).moveTo(index + 1);
        }
        sentenceRepository.saveAllAndFlush(ordered);
    }

    private double normalizedRate(double speechRate) {
        return speechRate == 0 ? 1.0 : speechRate;
    }

    private int defaultDifficulty(com.dictalab.domain.CefrLevel level) {
        return Math.min(5, level.ordinal() + 1);
    }

    private String normalizedAccent(String accentProfile) {
        return accentProfile == null ? "" : accentProfile.trim();
    }

    private void validateAudioSegment(Double start, Double end) {
        if ((start == null) != (end == null)) {
            throw new ContentManagementException("Mốc bắt đầu và kết thúc audio phải được nhập cùng nhau.");
        }
        if (start != null && end <= start) {
            throw new ContentManagementException("Mốc kết thúc audio phải lớn hơn mốc bắt đầu.");
        }
    }

    private void ensureContentEditable(Lesson lesson) {
        if (lesson.isActive()) {
            throw new ContentManagementException("Hãy gỡ xuất bản bài trước khi sửa câu hoặc audio.");
        }
        if (sessionRepository.existsByLessonId(lesson.getId())) {
            throw new ContentManagementException(
                    "Bài đã có lịch sử học nên nội dung câu được khóa để bảo toàn kết quả. Hãy tạo một bài mới."
            );
        }
        Integer listening = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM listening_units WHERE lesson_id=?", Integer.class, lesson.getId());
        if (listening != null && listening > 0)
            throw new ContentManagementException("Bài đã được dùng làm nguồn nghe hiểu. Hãy tạo bài học mới.");
    }

    private boolean contentLocked(Long lessonId) {
        return sessionRepository.existsByLessonId(lessonId) || Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM listening_units WHERE lesson_id=?)", Boolean.class, lessonId));
    }

    private void manageFilesAfterTransaction(String previousUrl, String newUrl) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    deleteMediaQuietly(previousUrl);
                } else {
                    deleteMediaQuietly(newUrl);
                }
            }
        });
    }

    private void deleteFileAfterCommit(String audioUrl) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteMediaQuietly(audioUrl);
            }
        });
    }

    private void deleteMediaQuietly(String audioUrl) {
        try {
            if(audioUrl!=null && audioUrl.startsWith("/media/")) {
                Integer references=jdbcTemplate.queryForObject("SELECT (SELECT COUNT(*) FROM lesson_sentences WHERE audio_url=?) + (SELECT COUNT(*) FROM listening_units WHERE audio_url=?)",Integer.class,audioUrl,audioUrl);
                if(references!=null && references>0) return;
            }
            mediaStorage.deleteIfManaged(audioUrl);
        } catch (RuntimeException exception) {
            LOGGER.warn("Không thể dọn file media không còn được tham chiếu: {}", audioUrl, exception);
        }
    }

    private Lesson requireLesson(Long lessonId) {
        return lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài nghe."));
    }

    private LessonSentence requireSentence(Long lessonId, Long sentenceId) {
        LessonSentence sentence = sentenceRepository.findById(sentenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy câu nghe."));
        if (!sentence.getLesson().getId().equals(lessonId)) {
            throw new ResourceNotFoundException("Câu nghe không thuộc bài này.");
        }
        return sentence;
    }

    private AdminDtos.LessonResponse toResponse(Lesson lesson) {
        List<AdminDtos.SentenceResponse> sentences = sentenceRepository
                .findByLessonIdOrderByPositionAsc(lesson.getId()).stream()
                .map(sentence -> new AdminDtos.SentenceResponse(
                        sentence.getId(), sentence.getPosition(), sentence.getTranscript(), sentence.getAudioUrl(),
                        sentence.getAudioStartSeconds(), sentence.getAudioEndSeconds(), sentence.getSpeechRate()
                )).toList();
        return new AdminDtos.LessonResponse(
                lesson.getId(), lesson.getSlug(), lesson.getTitle(), lesson.getDescription(), lesson.getLevel(),
                lesson.getTopic(), lesson.getEstimatedMinutes(), lesson.getDisplayOrder(), lesson.isActive(),
                lesson.getContentOrigin(), contentLocked(lesson.getId()),
                lesson.getSpeakerCount(), lesson.getAccentProfile(), lesson.getVocabularyDifficulty(),
                lesson.getGrammarDifficulty(), lesson.getSentenceDifficulty(), lesson.isAssessmentCalibrated(),
                lesson.getUpdatedAt(),
                sentences
        );
    }
}
