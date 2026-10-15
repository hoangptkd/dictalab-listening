package com.dictalab.repository;

import com.dictalab.domain.LessonPreviewWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonPreviewWordRepository extends JpaRepository<LessonPreviewWord, Long> {

    List<LessonPreviewWord> findByLessonIdOrderByPositionAsc(Long lessonId);

    @org.springframework.data.jpa.repository.Query("""
            select w from LessonPreviewWord w where w.lesson.id in :ids order by w.lesson.id,w.position
            """)
    List<LessonPreviewWord> findForLessons(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
