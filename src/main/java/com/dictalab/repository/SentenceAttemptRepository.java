package com.dictalab.repository;

import com.dictalab.domain.SentenceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SentenceAttemptRepository extends JpaRepository<SentenceAttempt, Long> {

    Optional<SentenceAttempt> findByPracticeSessionIdAndSentenceId(String practiceSessionId, Long sentenceId);

    List<SentenceAttempt> findByPracticeSessionIdOrderBySentencePositionAsc(String practiceSessionId);
}
