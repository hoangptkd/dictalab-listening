package com.dictalab.repository;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {

    List<Lesson> findByLevelAndActiveTrueOrderByDisplayOrder(CefrLevel level);

    List<Lesson> findByActiveTrueOrderByLevelAscDisplayOrderAsc();

    List<Lesson> findAllByOrderByUpdatedAtDesc();

    boolean existsBySlug(String slug);

    Optional<Lesson> findBySlug(String slug);

    List<Lesson> findByContentOrigin(String contentOrigin);

    interface CatalogLesson {
        Long getId();
        String getSlug();
        String getTitle();
        String getDescription();
        String getLevel();
        String getTopic();
        int getEstimatedMinutes();
        long getSentenceCount();
        String getAudioUrl();
        Integer getActivePosition();
        boolean getCompleted();
    }

    // Scalar subqueries avoid multiplying sentences, preview words and session history.
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT l.id, l.slug, l.title, l.description, l.level, l.topic,
                   l.estimated_minutes AS estimatedMinutes,
                   (SELECT COUNT(*) FROM lesson_sentences s WHERE s.lesson_id=l.id) AS sentenceCount,
                   (SELECT MAX(s.audio_url) FROM lesson_sentences s
                    WHERE s.lesson_id=l.id AND s.position_index=1) AS audioUrl,
                   (SELECT p.current_sentence_position FROM practice_sessions p
                    WHERE p.user_id=:userId AND p.lesson_id=l.id AND p.status='IN_PROGRESS'
                    ORDER BY p.started_at DESC, p.id DESC LIMIT 1) AS activePosition,
                   (done.lesson_id IS NOT NULL) AS completed
            FROM lessons l
            LEFT JOIN (SELECT p.lesson_id FROM practice_sessions p
                       WHERE p.user_id=:userId AND p.status='COMPLETED' GROUP BY p.lesson_id) done ON done.lesson_id=l.id
            WHERE l.level=:level AND l.active=TRUE
              AND (:administrator=TRUE OR l.content_origin <> 'BRITISH_COUNCIL_PERSONAL')
            ORDER BY l.display_order, l.id
            """, nativeQuery = true)
    List<CatalogLesson> catalog(@org.springframework.data.repository.query.Param("userId") Long userId,
                               @org.springframework.data.repository.query.Param("level") String level,
                               @org.springframework.data.repository.query.Param("administrator") boolean administrator);
}
