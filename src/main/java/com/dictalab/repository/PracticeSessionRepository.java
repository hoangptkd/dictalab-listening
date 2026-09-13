package com.dictalab.repository;

import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, String> {

    Optional<PracticeSession> findByIdAndUserId(String id, Long userId);

    Optional<PracticeSession> findFirstByUser_IdAndLesson_IdAndStatusOrderByStartedAtDesc(
            Long userId, Long lessonId, SessionStatus status
    );

    List<PracticeSession> findByUserIdAndStatusOrderByCompletedAtAsc(Long userId, SessionStatus status);
}
