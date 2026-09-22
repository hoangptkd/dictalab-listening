package com.dictalab.repository;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, String> {

    Optional<PracticeSession> findByIdAndUserId(String id, Long userId);

    Optional<PracticeSession> findFirstByUser_IdAndLesson_IdAndStatusOrderByStartedAtDesc(
            Long userId, Long lessonId, SessionStatus status
    );

    @EntityGraph(attributePaths = "lesson")
    List<PracticeSession> findByUserIdAndStatusOrderByCompletedAtAsc(Long userId, SessionStatus status);

    @EntityGraph(attributePaths = "lesson")
    List<PracticeSession> findByUserIdAndLessonLevelAndStatusOrderByStartedAtDesc(
            Long userId, CefrLevel level, SessionStatus status
    );

    @Query("""
            select distinct session.lesson.id
            from PracticeSession session
            where session.user.id = :userId
              and session.lesson.level = :level
              and session.status = :status
            """)
    List<Long> findDistinctLessonIds(
            @Param("userId") Long userId,
            @Param("level") CefrLevel level,
            @Param("status") SessionStatus status
    );

    boolean existsByLessonId(Long lessonId);

    boolean existsByUserIdAndLessonId(Long userId, Long lessonId);
}
