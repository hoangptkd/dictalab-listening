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
}
