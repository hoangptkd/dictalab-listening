package com.dictalab.repository;

import com.dictalab.domain.WordAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WordAttemptRepository extends JpaRepository<WordAttempt, Long> {

    Optional<WordAttempt> findBySentenceAttemptIdAndWordIndex(Long sentenceAttemptId, int wordIndex);

    List<WordAttempt> findBySentenceAttemptIdOrderByWordIndex(Long sentenceAttemptId);

    @Query("""
            select attempt from WordAttempt attempt
            join fetch attempt.sentenceAttempt sentenceAttempt
            where sentenceAttempt.practiceSession.id in :sessionIds
            """)
    List<WordAttempt> findBySessionIds(@Param("sessionIds") Collection<String> sessionIds);
}
