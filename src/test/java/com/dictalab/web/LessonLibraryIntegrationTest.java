package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.LessonLibrary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LessonLibraryIntegrationTest {
    @Autowired LessonLibrary library;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;

    @Test void guestCanPreviewWhileWritesRequireAdministratorAndCsrf() throws Exception {
        assertThat(library.snapshot(0,false).metadata()).allMatch(m -> m.auditNotes().isEmpty());
        assertThat(library.snapshot(0,true).metadata()).anyMatch(m -> !m.auditNotes().isEmpty());
        mvc.perform(get("/api/v1/library")).andExpect(status().isOk()).andExpect(jsonPath("$.courses.length()").value(15));
        mvc.perform(get("/api/admin/library")).andExpect(status().isUnauthorized());
        var owner = users.saveAndFlush(new AppUser("library-"+UUID.randomUUID()+"@test.local", "Learner", "unused", Instant.now()));
        var principal = DictaLabPrincipal.from(owner);
        mvc.perform(get("/api/admin/library").with(user(principal))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/library/courses").with(user(principal)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        db.update("UPDATE app_users SET role='ADMIN' WHERE id=?",owner.getId());
        var admin = new DictaLabPrincipal(owner.getId(), owner.getEmail(), "Administrator", "unused", "ADMIN", true);
        mvc.perform(get("/api/admin/library").with(user(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/admin/library/courses").with(user(admin)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/library/courses").with(user(admin)).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void sameOriginalLessonCanBelongToMultipleCoursesWithoutDuplicatingHistory() {
        var before = db.queryForObject("SELECT COUNT(*) FROM lessons", Integer.class);
        long id = lesson();
        String other = "communication-cefr-a2";
        String group = db.queryForObject("SELECT id FROM library_groups WHERE course_id=? ORDER BY position_index LIMIT 1", String.class, other);
        library.saveLink(new LessonLibrary.LinkEdit(other, group, id, 90, 2, "Reviewed purpose"));
        library.saveLink(new LessonLibrary.LinkEdit(other, group, id, 91, 2, "Updated purpose"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM library_lessons WHERE course_id=? AND lesson_id=?", Integer.class, other, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM lessons", Integer.class)).isEqualTo(before);
        assertThat(library.snapshot(0,false).memberships().stream().filter(m -> m.lessonId()==id).count()).isGreaterThan(1);
        library.unlink(other,id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM lessons", Integer.class)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM library_lessons WHERE course_id='communication-cefr-a1' AND lesson_id=?", Integer.class,id)).isEqualTo(1);
    }

    @Test void lessonCannotBeLinkedAcrossGoalsEvenToAnUnpublishedCourse() {
        long id = lesson();
        for (String course : new String[]{"ielts-band-3-0", "toeic-score-300", "ielts-foundation"}) {
            String group = db.queryForObject("SELECT id FROM library_groups WHERE course_id=? ORDER BY position_index LIMIT 1", String.class, course);
            assertThatThrownBy(() -> library.saveLink(new LessonLibrary.LinkEdit(course,group,id,90,2,"Purpose")))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Bài đã thuộc mục");
        }
        assertThat(db.queryForObject("""
                SELECT COUNT(DISTINCT c.goal) FROM library_lessons x JOIN library_courses c ON c.id=x.course_id
                WHERE x.lesson_id=?
                """, Integer.class,id)).isEqualTo(1);
    }

    @Test void changingCourseGoalCannotBypassLessonSeparation() {
        var course = library.snapshot(0,true).courses().stream().filter(c -> c.id().equals("communication-cefr-a1")).findFirst().orElseThrow();
        assertThatThrownBy(() -> library.saveCourse(new LessonLibrary.CourseEdit(course.id(),"IELTS",course.title(),course.targetLabel(),
                course.description(),course.criteria(),course.coverageNote(),course.position(),true,false)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Bài trong khóa đang thuộc mục khác");
        assertThat(db.queryForObject("SELECT goal FROM library_courses WHERE id=?",String.class,course.id())).isEqualTo("COMMUNICATION");
    }

    @Test void invalidatedAuditRemovesPublicAssignmentUntilReviewedAgain() {
        long id = lesson();
        db.update("UPDATE lessons SET updated_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().plusSeconds(5)), id);
        assertThat(library.snapshot(0,false).memberships()).noneMatch(m -> m.lessonId()==id);
        assertThat(library.snapshot(0,true).memberships()).anyMatch(m -> m.lessonId()==id);
        var group = db.queryForObject("SELECT group_id FROM library_lessons WHERE course_id='communication-cefr-a1' AND lesson_id=?",String.class,id);
        assertThatThrownBy(() -> library.saveLink(new LessonLibrary.LinkEdit("communication-cefr-a1", group,id,90,2,"Purpose")))
                .isInstanceOf(ResponseStatusException.class);
        library.saveMetadata(new LessonLibrary.MetadataEdit(id,"Daily life","Short details","Reviewed audio",null,null,"Transcript and audio checked",true));
        assertThat(library.snapshot(0,false).memberships()).anyMatch(m -> m.lessonId()==id);
    }

    @Test void privateContentAndMissingAudioCannotBeMarkedAsPublicReviewedContent() {
        long id = lesson();
        String origin = db.queryForObject("SELECT content_origin FROM lessons WHERE id=?",String.class,id);
        db.update("UPDATE lessons SET content_origin='BRITISH_COUNCIL_PERSONAL' WHERE id=?",id);
        assertThat(library.snapshot(0,false).lessons()).noneMatch(l -> l.id()==id);
        assertThatThrownBy(() -> library.saveMetadata(new LessonLibrary.MetadataEdit(id,"Topic","Focus","Source",null,null,"Checked",true)))
                .isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE lessons SET content_origin=? WHERE id=?",origin,id);
        db.update("UPDATE lesson_sentences SET audio_url='' WHERE lesson_id=?",id);
        assertThatThrownBy(() -> library.saveMetadata(new LessonLibrary.MetadataEdit(id,"Topic","Focus","Source",null,null,"Checked",true)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void everyGroupRequiresContentBeforeCourseCanBeOpened() {
        var course = library.snapshot(0,true).courses().stream().filter(c -> c.id().equals("ielts-band-7-0")).findFirst();
        String id = course.map(LessonLibrary.Course::id).orElseGet(() -> library.snapshot(0,true).courses().stream()
                .filter(c -> c.goal().equals("IELTS") && !c.ready()).findFirst().orElseThrow().id());
        assertThatThrownBy(() -> library.saveCourse(new LessonLibrary.CourseEdit(id,"IELTS","Course","Target","Description","Criteria","Missing content",2,true,true)))
                .isInstanceOf(ResponseStatusException.class);
        library.saveGroup(new LessonLibrary.GroupEdit("empty-test-group","communication-cefr-a1","New empty group",90));
        assertThat(library.snapshot(0,false).courses().stream().filter(c -> c.id().equals("communication-cefr-a1")).findFirst().orElseThrow().ready()).isFalse();
    }

    @Test void reviewedConversationsBelongOnlyToCommunicationAndExamTiersAwaitSeparateContent() {
        var publicView = library.snapshot(0,false);
        assertThat(publicView.courses().stream().filter(c -> c.goal().equals("IELTS")).map(LessonLibrary.Course::title))
                .containsExactly("Band 3.0","Band 4.0","Band 5.0","Band 6.0","Band 7.0","Band 8.0+");
        assertThat(publicView.courses().stream().filter(c -> c.goal().equals("TOEIC")).map(LessonLibrary.Course::title))
                .containsExactly("300","500","700","900+");
        assertThat(publicView.courses().stream().filter(c -> c.goal().equals("COMMUNICATION")).map(LessonLibrary.Course::title))
                .containsExactly("A1","A2","B1","B2","C1");
        // Fresh test databases have the 20 bundled lessons; the 3 production AI lessons are not seeded.
        assertThat(publicView.courses().stream().filter(LessonLibrary.Course::ready)).hasSize(4);
        assertThat(publicView.courses()).filteredOn(c -> !c.goal().equals("COMMUNICATION")).allMatch(c -> !c.ready());
        assertThat(publicView.courses()).filteredOn(c -> c.title().equals("Band 7.0") || c.title().equals("Band 8.0+") || c.title().equals("900+") || c.title().equals("C1"))
                .allMatch(c -> !c.ready());
        assertThat(publicView.courses()).noneMatch(c -> c.id().equals("ielts-foundation"));
        assertThat(library.snapshot(0,true).courses()).anyMatch(c -> c.id().equals("ielts-foundation") && !c.published());
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM library_lessons x JOIN library_courses c ON c.id=x.course_id
                WHERE c.goal IN ('IELTS','TOEIC')
                """, Integer.class)).isZero();
        for (var tier : publicView.courses()) {
            if (tier.goal().equals("COMMUNICATION")) {
                assertThat(publicView.memberships().stream().filter(m -> m.courseId().equals(tier.id())))
                        .allMatch(m -> publicView.lessons().stream().anyMatch(l -> l.id()==m.lessonId() && l.level().name().equals(tier.title())));
            }
        }
        for (String goal : new String[]{"COMMUNICATION"}) {
            var courseIds = publicView.courses().stream().filter(c -> c.goal().equals(goal)).map(LessonLibrary.Course::id).toList();
            var assignments = publicView.memberships().stream().filter(m -> courseIds.contains(m.courseId())).map(LessonLibrary.Membership::lessonId).toList();
            assertThat(assignments).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(publicView.lessons().stream()
                    .filter(l -> l.slug().startsWith("elllo-sentences-")).map(l -> l.id()).toList());
        }
    }

    @Test void completedLessonKeepsCompletionWhileSoloReplayAndOldRoomRemainSeparate() {
        var owner = users.saveAndFlush(new AppUser("library-progress-"+UUID.randomUUID()+"@test.local", "Learner", "unused", Instant.now()));
        long lesson = lesson();
        session(owner,lesson,"COMPLETED",5,Instant.now().minusSeconds(20));
        String old = session(owner,lesson,"IN_PROGRESS",4,Instant.now().minusSeconds(10));
        db.update("UPDATE practice_sessions SET room_id=? WHERE id=?", UUID.randomUUID().toString(),old);
        String newest = session(owner,lesson,"IN_PROGRESS",1,Instant.now());
        var result = library.snapshot(owner.getId(),false);
        var l = result.lessons().stream().filter(item -> item.id()==lesson).findFirst().orElseThrow();
        assertThat(l.completed()).isTrue(); assertThat(l.inProgress()).isTrue(); assertThat(l.progressPercent()).isZero();
        assertThat(result.activeSessions().get(0).sessionId()).isEqualTo(newest);
        assertThat(library.snapshot(0,false).activeSessions()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_sessions WHERE id IN (?,?)", Integer.class,old,newest)).isEqualTo(2);
    }
    private long lesson() { return db.queryForObject("SELECT lesson_id FROM library_lessons WHERE course_id='communication-cefr-a1' ORDER BY group_id,position_index LIMIT 1",Long.class); }
    private String session(AppUser user,long lesson,String status,int position,Instant time) {
        String id=UUID.randomUUID().toString();
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,current_sentence_position,
                started_at,completed_at,total_replays,hint_count,reveal_count,known_missed_count,unknown_count,
                total_practice_seconds,first_attempt_accuracy,final_accuracy,overall_score,first_exposure,version)
                VALUES (?,?,?,?,?,?,?, ?,0,0,0,0,0,0,0,0,0,TRUE,0)
                """,id,user.getEmail(),user.getId(),lesson,status,position,java.sql.Timestamp.from(time),status.equals("COMPLETED") ? java.sql.Timestamp.from(time) : null);
        return id;
    }
}
