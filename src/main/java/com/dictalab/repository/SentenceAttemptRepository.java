package com.dictalab.repository;

import com.dictalab.domain.SentenceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SentenceAttemptRepository extends JpaRepository<SentenceAttempt, Long> {

    Optional<SentenceAttempt> findByPracticeSessionIdAndSentenceId(String practiceSessionId, Long sentenceId);

    @Query("""
            select attempt from SentenceAttempt attempt
            join fetch attempt.sentence sentence
            where attempt.practiceSession.id = :sessionId and sentence.id = :sentenceId
              and sentence.position = :position and sentence.lesson.id = :lessonId
            """)
    Optional<SentenceAttempt> findCurrent(@Param("sessionId") String sessionId,
                                         @Param("sentenceId") Long sentenceId,
                                         @Param("position") int position,
                                         @Param("lessonId") Long lessonId);

    @Query("""
            select attempt from SentenceAttempt attempt
            join fetch attempt.sentence sentence
            where attempt.practiceSession.id = :practiceSessionId
            order by sentence.position asc
            """)
    List<SentenceAttempt> findByPracticeSessionIdOrderBySentencePositionAsc(
            @Param("practiceSessionId") String practiceSessionId);

    @Query("""
            select attempt from SentenceAttempt attempt
            join fetch attempt.practiceSession session
            join fetch attempt.sentence
            where session.id in :sessionIds
            """)
    List<SentenceAttempt> findBySessionIds(@Param("sessionIds") Collection<String> sessionIds);
}
