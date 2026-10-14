package com.dictalab.repository;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, String> {

    Optional<PracticeSession> findByIdAndUserId(String id, Long userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PracticeSession s where s.id = :id and s.user.id = :userId")
    Optional<PracticeSession> lockOwned(@Param("id") String id, @Param("userId") Long userId);

    Optional<PracticeSession> findFirstByUser_IdAndLesson_IdAndRoomIdIsNullAndStatusOrderByStartedAtDesc(
            Long userId, Long lessonId, SessionStatus status
    );

    Optional<PracticeSession> findFirstByUserIdAndRoomIdOrderByStartedAtDesc(Long userId, String roomId);

    @EntityGraph(attributePaths = "lesson")
    List<PracticeSession> findByUserIdAndStatusOrderByCompletedAtAsc(Long userId, SessionStatus status);

    @EntityGraph(attributePaths = "lesson")
    List<PracticeSession> findByUserIdAndStatusAndRoomIdIsNullOrderByCompletedAtDescIdDesc(
            Long userId, SessionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "lesson")
    List<PracticeSession> findByUserIdAndStatusAndRoomIdIsNullAndLessonLevelOrderByCompletedAtDescIdDesc(
            Long userId, SessionStatus status, CefrLevel level, Pageable pageable);

    @EntityGraph(attributePaths = "lesson")
    @Query(value = """
            select s from PracticeSession s
            where s.user.id = :userId and s.status = :status
              and (:level is null or s.lesson.level = :level)
            """, countQuery = """
            select count(s) from PracticeSession s
            where s.user.id = :userId and s.status = :status
              and (:level is null or s.lesson.level = :level)
            """)
    Page<PracticeSession> history(@Param("userId") Long userId, @Param("status") SessionStatus status,
                                  @Param("level") CefrLevel level, Pageable pageable);

    interface LevelTotals {
        CefrLevel getLevel();
        long getCompletedLessons();
        long getPracticeSeconds();
        double getFirstAccuracySum();
        double getFinalAccuracySum();
        double getScoreSum();
        long getKnownMissed();
        long getUnknownWords();
    }

    @Query("""
            select s.lesson.level as level, count(s) as completedLessons,
                   sum(s.totalPracticeSeconds) as practiceSeconds,
                   sum(s.firstAttemptAccuracy) as firstAccuracySum,
                   sum(s.finalAccuracy) as finalAccuracySum, sum(s.overallScore) as scoreSum,
                   sum(s.knownMissedCount) as knownMissed, sum(s.unknownCount) as unknownWords
            from PracticeSession s
            where s.user.id = :userId and s.status = :status and s.roomId is null
            group by s.lesson.level
            """)
    List<LevelTotals> dashboardTotals(@Param("userId") Long userId, @Param("status") SessionStatus status);

    @EntityGraph(attributePaths = "lesson")
    @Query("""
            select s from PracticeSession s
            where s.user.id = :userId and s.status = :status and s.roomId is null and s.firstExposure = true
              and not exists (
                select other.id from PracticeSession other
                where other.user.id = s.user.id and other.lesson.id = s.lesson.id
                  and other.status = :status and other.roomId is null and other.firstExposure = true
                  and (other.completedAt > s.completedAt or (other.completedAt = s.completedAt and other.id > s.id))
              )
            order by s.completedAt desc, s.id desc
            """)
    List<PracticeSession> assessmentEvidence(@Param("userId") Long userId,
                                             @Param("status") SessionStatus status, Pageable pageable);

    @Query("""
            select distinct s.lesson.id from PracticeSession s
            where s.user.id = :userId and s.status = :status and s.roomId is null and s.lesson.level = :level
            """)
    List<Long> completedSoloLessonIds(@Param("userId") Long userId, @Param("status") SessionStatus status,
                                      @Param("level") CefrLevel level);

    interface CatalogProgress {
        Long getLessonId();
        Integer getActivePosition();
        Integer getCompleted();
    }

    // One row per lesson; retain the newest active session, including room sessions.
    @Query("""
            select s.lesson.id as lessonId,
                   max(case when s.status = :active then s.currentSentencePosition else null end) as activePosition,
                   max(case when s.status = :completed then 1 else 0 end) as completed
            from PracticeSession s
            where s.user.id = :userId and s.lesson.level = :level
              and (s.status = :completed or (s.status = :active and not exists (
                select newer.id from PracticeSession newer
                where newer.user.id = s.user.id and newer.lesson.id = s.lesson.id
                  and newer.status = :active
                  and (newer.startedAt > s.startedAt or (newer.startedAt = s.startedAt and newer.id > s.id))
              )))
            group by s.lesson.id
            """)
    List<CatalogProgress> catalogProgress(@Param("userId") Long userId, @Param("level") CefrLevel level,
                                         @Param("active") SessionStatus active, @Param("completed") SessionStatus completed);

    boolean existsByLessonId(Long lessonId);

    boolean existsByUserIdAndLessonId(Long userId, Long lessonId);
}
