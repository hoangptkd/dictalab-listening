package com.dictalab.repository;

import com.dictalab.domain.WordAttemptEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WordAttemptEventRepository extends JpaRepository<WordAttemptEvent, Long> {

    @Query("""
            select event from WordAttemptEvent event
            join fetch event.wordAttempt wordAttempt
            where wordAttempt.sentenceAttempt.practiceSession.id in :sessionIds
            order by event.createdAt asc
            """)
    List<WordAttemptEvent> findBySessionIds(@Param("sessionIds") Collection<String> sessionIds);
}
