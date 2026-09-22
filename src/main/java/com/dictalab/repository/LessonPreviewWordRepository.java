package com.dictalab.repository;

import com.dictalab.domain.LessonPreviewWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonPreviewWordRepository extends JpaRepository<LessonPreviewWord, Long> {

    List<LessonPreviewWord> findByLessonIdOrderByPositionAsc(Long lessonId);
}
