package com.dictalab.service;

import com.dictalab.dto.ApiDtos;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;

@Service
@Transactional(readOnly = true)
public class LessonLibrary {
    private final JdbcTemplate db;
    private final CatalogService catalog;

    public LessonLibrary(JdbcTemplate db, CatalogService catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    public record Course(String id, String goal, String title, String targetLabel, String description,
                         String criteria, String coverageNote, int position, boolean published, boolean ready, int revision) {}
    public record Group(String id, String courseId, String title, int position) {}
    public record Membership(String courseId, String groupId, long lessonId, int position, int difficulty, String purpose) {}
    public record Metadata(long lessonId, String topic, String focus, String source, String accent,
                           Integer speakerCount, String auditNotes, boolean reviewed) {}
    public record Active(String sessionId, long lessonId, int position) {}
    public record Snapshot(List<ApiDtos.LessonSummary> lessons, List<Course> courses, List<Group> groups,
                           List<Membership> memberships, List<Metadata> metadata, List<Active> activeSessions) {}

    public Snapshot snapshot(long userId, boolean administrator) {
        List<ApiDtos.LessonSummary> lessons = catalog.lessons(userId, null, administrator);
        var courses = db.query("SELECT * FROM library_courses WHERE published=TRUE OR ?=TRUE ORDER BY goal,position_index,id",
                (r, n) -> new Course(r.getString("id"), r.getString("goal"), r.getString("title"), r.getString("target_label"),
                        r.getString("description"), r.getString("criteria"), r.getString("coverage_note"),
                        r.getInt("position_index"), r.getBoolean("published"), r.getBoolean("ready"), r.getInt("revision")), administrator);
        var groups = db.query("""
                SELECT g.* FROM library_groups g JOIN library_courses c ON c.id=g.course_id
                WHERE c.published=TRUE OR ?=TRUE ORDER BY g.course_id,g.position_index,g.id
                """, (r, n) -> new Group(r.getString("id"), r.getString("course_id"), r.getString("title"), r.getInt("position_index")), administrator);
        var memberships = db.query("""
                SELECT x.* FROM library_lessons x JOIN library_courses c ON c.id=x.course_id
                JOIN lessons l ON l.id=x.lesson_id JOIN library_lesson_metadata m ON m.lesson_id=l.id
                WHERE l.active=TRUE AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL'
                    AND (?=TRUE OR (m.reviewed=TRUE AND m.lesson_updated_at=l.updated_at))
                    AND (c.published=TRUE OR ?=TRUE)
                ORDER BY x.course_id,x.group_id,x.position_index,x.lesson_id
                """, (r, n) -> new Membership(r.getString("course_id"), r.getString("group_id"), r.getLong("lesson_id"),
                        r.getInt("position_index"), r.getInt("difficulty"), r.getString("purpose")), administrator, administrator);
        var metadata = db.query("""
                SELECT m.*, (m.reviewed=TRUE AND m.lesson_updated_at=l.updated_at) AS current_review
                FROM library_lesson_metadata m JOIN lessons l ON l.id=m.lesson_id
                WHERE l.active=TRUE AND (l.content_origin<>'BRITISH_COUNCIL_PERSONAL' OR ?=TRUE)
                ORDER BY m.lesson_id
                """, (r, n) -> new Metadata(r.getLong("lesson_id"), r.getString("topic"), r.getString("focus"),
                        r.getString("source_label"), r.getString("accent"), r.getObject("speaker_count", Integer.class),
                        administrator ? r.getString("audit_notes") : "", r.getBoolean("current_review")), administrator);
        var active = db.query("""
                SELECT p.id,p.lesson_id,p.current_sentence_position FROM practice_sessions p JOIN lessons l ON l.id=p.lesson_id
                WHERE p.user_id=? AND p.status='IN_PROGRESS' AND p.room_id IS NULL AND l.active=TRUE
                    AND (l.content_origin<>'BRITISH_COUNCIL_PERSONAL' OR ?=TRUE)
                ORDER BY p.started_at DESC,p.id DESC
                """, (r, n) -> new Active(r.getString("id"), r.getLong("lesson_id"), r.getInt("current_sentence_position")), userId, administrator);
        var latest = new HashMap<Long, Active>();
        active.forEach(item -> latest.putIfAbsent(item.lessonId(), item));
        lessons = lessons.stream().map(l -> {
            Active current = latest.get(l.id());
            int percent = current == null || l.sentenceCount() == 0 ? 0
                    : Math.min(99, Math.max(0, (current.position() - 1) * 100 / l.sentenceCount()));
            return new ApiDtos.LessonSummary(l.id(), l.slug(), l.title(), l.description(), l.level(), l.topic(),
                    l.estimatedMinutes(), l.sentenceCount(), percent, current != null, l.completed(), l.previewAudioUrl(), l.previewWords());
        }).toList();
        var reviewed = new HashSet<Long>();
        metadata.stream().filter(Metadata::reviewed).forEach(m -> reviewed.add(m.lessonId()));
        courses = courses.stream().map(c -> {
            var courseGroups = groups.stream().filter(g -> g.courseId().equals(c.id())).toList();
            boolean covered = !courseGroups.isEmpty() && courseGroups.stream().allMatch(g -> memberships.stream()
                    .anyMatch(m -> m.groupId().equals(g.id()) && reviewed.contains(m.lessonId())));
            return new Course(c.id(), c.goal(), c.title(), c.targetLabel(), c.description(), c.criteria(), c.coverageNote(),
                    c.position(), c.published(), c.ready() && covered, c.revision());
        }).toList();
        return new Snapshot(lessons, courses, groups, memberships, metadata, active);
    }

    public record CourseEdit(@Pattern(regexp="[a-z0-9-]{1,80}") @NotBlank String id,
                             @Pattern(regexp="IELTS|TOEIC|COMMUNICATION") @NotBlank String goal,
                             @NotBlank @Size(max=180) String title, @NotBlank @Size(max=200) String targetLabel,
                             @NotBlank @Size(max=1000) String description, @NotBlank @Size(max=3000) String criteria,
                             @NotBlank @Size(max=1000) String coverageNote, @Min(1) @Max(10000) int position,
                             boolean published, boolean ready) {}
    public record GroupEdit(@Pattern(regexp="[a-z0-9-]{1,100}") @NotBlank String id,
                            @Pattern(regexp="[a-z0-9-]{1,80}") @NotBlank String courseId,
                            @NotBlank @Size(max=180) String title, @Min(1) @Max(10000) int position) {}
    public record LinkEdit(@Pattern(regexp="[a-z0-9-]{1,80}") @NotBlank String courseId,
                           @Pattern(regexp="[a-z0-9-]{1,100}") @NotBlank String groupId,
                           @Positive long lessonId, @Min(1) @Max(10000) int position,
                           @Min(1) @Max(5) int difficulty, @NotBlank @Size(max=1000) String purpose) {}
    public record MetadataEdit(@Positive long lessonId, @NotBlank @Size(max=180) String topic,
                               @NotBlank @Size(max=180) String focus, @NotBlank @Size(max=180) String source,
                               @Size(max=120) String accent, @Min(1) @Max(20) Integer speakerCount,
                               @NotBlank @Size(max=3000) String auditNotes, boolean reviewed) {}

    @Transactional
    public void saveCourse(CourseEdit e) {
        if (db.queryForObject("SELECT COUNT(*) FROM library_courses WHERE id=?", Integer.class, e.id()) == 0) {
            db.update("""
                    INSERT INTO library_courses(id,goal,title,target_label,description,criteria,coverage_note,position_index,published,ready)
                    VALUES (?,?,?,?,?,?,?,?,?,FALSE)
                    """, e.id(), e.goal(), e.title(), e.targetLabel(), e.description(), e.criteria(), e.coverageNote(), e.position(), e.published());
        }
        lockCourse(e.id());
        var currentGoal = db.queryForObject("SELECT goal FROM library_courses WHERE id=?", String.class, e.id());
        if (!e.goal().equals(currentGoal)) {
            db.queryForList("""
                    SELECT l.id FROM lessons l JOIN library_lessons x ON x.lesson_id=l.id
                    WHERE x.course_id=? ORDER BY l.id FOR UPDATE OF l
                    """, Long.class, e.id());
            if (db.queryForObject("""
                    SELECT COUNT(*) FROM library_lessons x JOIN library_lessons other ON other.lesson_id=x.lesson_id
                    JOIN library_courses c ON c.id=other.course_id
                    WHERE x.course_id=? AND other.course_id<>? AND c.goal<>?
                    """, Integer.class, e.id(), e.id(), e.goal()) > 0)
                invalid("Bài trong khóa đang thuộc mục khác. Bỏ liên kết cũ trước khi chuyển mục.");
        }
        if (e.ready()) requireCoverage(e.id());
        db.update("""
                UPDATE library_courses SET goal=?,title=?,target_label=?,description=?,criteria=?,coverage_note=?,
                    position_index=?,published=?,ready=?,revision=revision+1 WHERE id=?
                """, e.goal(), e.title(), e.targetLabel(), e.description(), e.criteria(), e.coverageNote(),
                e.position(), e.published(), e.ready(), e.id());
    }

    @Transactional
    public void saveGroup(GroupEdit e) {
        lockCourse(e.courseId());
        var owner = db.queryForList("SELECT course_id FROM library_groups WHERE id=?", String.class, e.id());
        if (!owner.isEmpty() && !owner.get(0).equals(e.courseId())) invalid("Không chuyển nhóm sang khóa khác khi còn liên kết bài.");
        if (db.update("UPDATE library_groups SET title=?,position_index=? WHERE id=? AND course_id=?",
                e.title(), e.position(), e.id(), e.courseId()) == 0) {
            db.update("INSERT INTO library_groups(id,course_id,title,position_index) VALUES (?,?,?,?)",
                    e.id(), e.courseId(), e.title(), e.position());
        }
        // A new empty group needs content before the course can again be marked ready.
        db.update("UPDATE library_courses SET ready=FALSE,revision=revision+1 WHERE id=?", e.courseId());
    }

    @Transactional
    public void saveLink(LinkEdit e) {
        lockCourse(e.courseId());
        if (db.queryForObject("SELECT COUNT(*) FROM library_groups WHERE id=? AND course_id=?", Integer.class, e.groupId(), e.courseId()) == 0)
            invalid("Nhóm bài không thuộc khóa đã chọn.");
        // Serialize assignments across courses so concurrent edits cannot mix goals.
        db.queryForList("SELECT id FROM lessons WHERE id=? FOR UPDATE", Long.class, e.lessonId());
        if (db.queryForObject("""
                SELECT COUNT(*) FROM library_lessons x JOIN library_courses c ON c.id=x.course_id
                WHERE x.lesson_id=? AND c.goal<>(SELECT goal FROM library_courses WHERE id=?)
                """, Integer.class, e.lessonId(), e.courseId()) > 0)
            invalid("Bài đã thuộc mục IELTS, TOEIC hoặc Giao tiếp khác. Bỏ liên kết ở mục cũ trước khi chuyển.");
        if (db.queryForObject("""
                SELECT COUNT(*) FROM lessons l JOIN library_lesson_metadata m ON m.lesson_id=l.id
                WHERE l.id=? AND l.active=TRUE AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL'
                    AND m.reviewed=TRUE AND m.lesson_updated_at=l.updated_at
                """, Integer.class, e.lessonId()) == 0) invalid("Bài phải công khai, đang hoạt động và đã kiểm tra transcript/audio hiện tại.");
        if (db.update("""
                UPDATE library_lessons SET group_id=?,position_index=?,difficulty=?,purpose=? WHERE course_id=? AND lesson_id=?
                """, e.groupId(), e.position(), e.difficulty(), e.purpose(), e.courseId(), e.lessonId()) == 0) {
            db.update("INSERT INTO library_lessons(course_id,group_id,lesson_id,position_index,difficulty,purpose) VALUES (?,?,?,?,?,?)",
                    e.courseId(), e.groupId(), e.lessonId(), e.position(), e.difficulty(), e.purpose());
        }
        db.update("UPDATE library_courses SET revision=revision+1 WHERE id=?", e.courseId());
    }

    @Transactional
    public void unlink(String courseId, long lessonId) {
        lockCourse(courseId);
        db.update("DELETE FROM library_lessons WHERE course_id=? AND lesson_id=?", courseId, lessonId);
        db.update("UPDATE library_courses SET ready=FALSE,revision=revision+1 WHERE id=?", courseId);
    }

    @Transactional
    public void saveMetadata(MetadataEdit e) {
        var times = db.queryForList("SELECT updated_at FROM lessons WHERE id=? AND content_origin<>'BRITISH_COUNCIL_PERSONAL' FOR UPDATE",
                java.sql.Timestamp.class, e.lessonId());
        if (times.isEmpty()) invalid("Không dùng nội dung riêng tư để xếp khóa công khai.");
        if (e.reviewed() && db.queryForObject("""
                SELECT COUNT(*) FROM lesson_sentences WHERE lesson_id=? AND
                    (transcript IS NULL OR TRIM(transcript)='' OR audio_url IS NULL OR TRIM(audio_url)='')
                """, Integer.class, e.lessonId()) > 0) invalid("Bài còn thiếu transcript hoặc audio.");
        if (e.reviewed() && db.queryForObject("SELECT COUNT(*) FROM lesson_sentences WHERE lesson_id=?", Integer.class, e.lessonId()) == 0)
            invalid("Bài chưa có câu nghe.");
        if (db.update("""
                UPDATE library_lesson_metadata SET topic=?,focus=?,source_label=?,accent=?,speaker_count=?,audit_notes=?,reviewed=?,lesson_updated_at=? WHERE lesson_id=?
                """, e.topic(), e.focus(), e.source(), blankToNull(e.accent()), e.speakerCount(), e.auditNotes(), e.reviewed(), times.get(0), e.lessonId()) == 0) {
            db.update("""
                    INSERT INTO library_lesson_metadata(lesson_id,topic,focus,source_label,accent,speaker_count,audit_notes,reviewed,lesson_updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, e.lessonId(), e.topic(), e.focus(), e.source(), blankToNull(e.accent()), e.speakerCount(), e.auditNotes(), e.reviewed(), times.get(0));
        }
    }

    private void lockCourse(String id) {
        if (db.queryForList("SELECT id FROM library_courses WHERE id=? FOR UPDATE", String.class, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy khóa học.");
    }

    private void requireCoverage(String id) {
        int groups = db.queryForObject("SELECT COUNT(*) FROM library_groups WHERE course_id=?", Integer.class, id);
        int missing = db.queryForObject("""
                SELECT COUNT(*) FROM library_groups g WHERE g.course_id=? AND NOT EXISTS (
                    SELECT 1 FROM library_lessons x JOIN lessons l ON l.id=x.lesson_id
                    JOIN library_lesson_metadata m ON m.lesson_id=l.id WHERE x.group_id=g.id AND l.active=TRUE
                    AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL' AND m.reviewed=TRUE AND m.lesson_updated_at=l.updated_at)
                """, Integer.class, id);
        if (groups == 0 || missing != 0) invalid("Mỗi nhóm cần ít nhất một bài công khai đã kiểm tra trước khi mở khóa học.");
    }

    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }
    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
