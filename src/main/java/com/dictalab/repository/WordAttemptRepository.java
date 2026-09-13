package com.dictalab.repository;

import com.dictalab.domain.WordAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WordAttemptRepository extends JpaRepository<WordAttempt, Long> {

    Optional<WordAttempt> findBySentenceAttemptIdAndWordIndex(Long sentenceAttemptId, int wordIndex);

    List<WordAttempt> findBySentenceAttemptIdOrderByWordIndex(Long sentenceAttemptId);
}
