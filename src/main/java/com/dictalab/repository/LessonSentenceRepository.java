package com.dictalab.repository;

import com.dictalab.domain.LessonSentence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonSentenceRepository extends JpaRepository<LessonSentence, Long> {

    List<LessonSentence> findByLessonIdOrderByPositionAsc(Long lessonId);

    Optional<LessonSentence> findByLessonIdAndPosition(Long lessonId, int position);

    long countByLessonId(Long lessonId);
}
