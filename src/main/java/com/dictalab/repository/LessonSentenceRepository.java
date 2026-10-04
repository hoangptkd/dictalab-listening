package com.dictalab.repository;

import com.dictalab.domain.LessonSentence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonSentenceRepository extends JpaRepository<LessonSentence, Long> {

    List<LessonSentence> findByLessonIdOrderByPositionAsc(Long lessonId);

    Optional<LessonSentence> findByLessonIdAndPosition(Long lessonId, int position);

    long countByLessonId(Long lessonId);

    interface Summary {
        Long getLessonId();
        long getSentenceCount();
        String getAudioUrl();
    }

    @org.springframework.data.jpa.repository.Query("""
            select s.lesson.id as lessonId, count(s) as sentenceCount,
                   max(case when s.position=1 then s.audioUrl else null end) as audioUrl
            from LessonSentence s where s.lesson.id in :ids group by s.lesson.id
            """)
    List<Summary> summaries(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
