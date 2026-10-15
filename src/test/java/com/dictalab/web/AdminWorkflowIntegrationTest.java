package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.domain.CefrLevel;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired ObjectMapper json;
    @Autowired LessonRepository lessons;
    @Autowired LessonSentenceRepository sentences;
    DictaLabPrincipal admin;
    String prefix;
    @BeforeEach void setup() {
        prefix="audit-"+UUID.randomUUID();
        var account=new AppUser(prefix+"@test.local","QA Admin","test-only",Instant.now());
        account.promoteToAdmin(Instant.now()); admin=DictaLabPrincipal.from(users.saveAndFlush(account));
    }
    @Test void pagedListsRejectLearnersAndInvalidFilters() throws Exception {
        for (String route : new String[]{"/api/admin/lessons/page","/api/admin/listening/units/page","/api/admin/users/page","/api/admin/support/tickets/page"}) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            mvc.perform(get(route).with(user("learner").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(route).with(user(admin)).param("sort","title; DROP TABLE app_users")) .andExpect(status().isBadRequest());
            mvc.perform(get(route).with(user(admin)).param("page","-1")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("from","2026-99-99")).andExpect(status().isBadRequest());
    }
    @Test void userFiltersCombineAndClampPageWithoutExposingSecrets() throws Exception {
        var learner=users.saveAndFlush(new AppUser(prefix+"-learner@test.local",prefix,"test-secret",Instant.now()));
        mvc.perform(get("/api/admin/users/page").with(user(admin)).param("q",prefix).param("role","USER").param("verified","false").param("enabled","true").param("page","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items[0].id").value(learner.getId())).andExpect(jsonPath("$.items[0].password_hash").doesNotExist());
        mvc.perform(get("/api/admin/users/page").with(user(admin)).param("q","%")).andExpect(jsonPath("$.total").value(0));
    }
    @Test void lessonEditsRejectStaleFormAndRecordActor() throws Exception {
        String body=json.writeValueAsString(Map.of("slug",prefix,"title",prefix,"description","QA lesson","level","A1","topic",prefix,"estimatedMinutes",5,"displayOrder",500));
        var created=json.readTree(mvc.perform(post("/api/admin/lessons").with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id=created.path("id").asLong();
        var original=json.readTree(mvc.perform(get("/api/admin/lessons/"+id).with(user(admin))).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/admin/lessons/"+id).with(user(admin)).with(csrf()).header("X-Edit-Version",original.path("updatedAt").asText()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/lessons/"+id).with(user(admin)).with(csrf()).header("X-Edit-Version",original.path("updatedAt").asText()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/lessons/page").with(user(admin)).param("q",prefix).param("level","A1").param("status","DRAFT").param("origin","ADMIN_UPLOAD")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/lessons/"+id+"/events").with(user(admin))).andExpect(jsonPath("$[0].actor_id").value(admin.id())).andExpect(jsonPath("$[0].action").value("UPDATE_METADATA"));
    }
    @Test void ticketFilterVersionsAndResponseHistory() throws Exception {
        var learner=users.saveAndFlush(new AppUser(prefix+"-learner@test.local",prefix,"test-only",Instant.now()));
        db.update("INSERT INTO support_tickets(user_id,category,subject,description) VALUES (?,?,?,?)",learner.getId(),"AUDIO",prefix,"Audio error");
        long id=db.queryForObject("SELECT id FROM support_tickets WHERE user_id=?",Long.class,learner.getId());
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("q",prefix).param("category","AUDIO").param("status","OPEN")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(put("/api/admin/support/tickets/"+id).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"adminNote\":\"First response\",\"version\":0}")).andExpect(status().isOk());
        mvc.perform(put("/api/admin/support/tickets/"+id).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"adminNote\":\"Stale response\",\"version\":0}")).andExpect(status().isConflict());
        mvc.perform(put("/api/admin/support/tickets/"+id).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"adminNote\":\"Second response\",\"version\":1}")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/support/tickets/"+id).with(user(admin))).andExpect(jsonPath("$.edit_version").value(2)).andExpect(jsonPath("$.events.length()").value(2)).andExpect(jsonPath("$.events[1].detail").value("First response"));
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("q",prefix))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("q",prefix).param("status",""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].status").value("RESOLVED"));
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("q",prefix).param("status","RESOLVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/support/tickets/page").with(user(admin)).param("status","INVALID"))
                .andExpect(status().isBadRequest());
    }
    @Test void listeningDraftVersionAndArchiveReleaseSource() throws Exception {
        long source=db.queryForObject("SELECT u.id FROM listening_units u WHERE u.published=TRUE AND NOT EXISTS(SELECT 1 FROM listening_units d WHERE d.lesson_id=u.lesson_id AND d.audio_url=u.audio_url AND d.published=FALSE AND d.retired=FALSE) ORDER BY u.id LIMIT 1",Long.class);
        var copied=json.readTree(mvc.perform(post("/api/admin/listening/units/"+source+"/copy").with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long id=copied.path("id").asLong();
        var unit=json.readTree(mvc.perform(get("/api/admin/listening/units/"+id).with(user(admin))).andReturn().getResponse().getContentAsString());
        assertThat(unit.path("transcript").size()).isGreaterThanOrEqualTo(5);
        String body=json.writeValueAsString(Map.of("title",prefix,"content",json.readTree(unit.path("content_json").asText())));
        mvc.perform(put("/api/admin/listening/units/"+id).with(user(admin)).with(csrf()).header("X-Edit-Version",0).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/listening/units/"+id).with(user(admin)).with(csrf()).header("X-Edit-Version",0).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/listening/units/page").with(user(admin)).param("q",prefix).param("status","DRAFT")).andExpect(jsonPath("$.items[0].content_json").doesNotExist()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(put("/api/admin/listening/units/"+id+"/retire").with(user(admin)).with(csrf()).header("X-Edit-Version",1)).andExpect(status().isOk());
        var second=json.readTree(mvc.perform(post("/api/admin/listening/units/"+source+"/copy").with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/admin/listening/units/"+second.path("id").asLong()+"/retire").with(user(admin)).with(csrf())).andExpect(status().isOk());
    }
    @Test void copiedLessonPreservesAudioIdentityAndKeepsFileUntilLastReference() throws Exception {
        Path media=Path.of("target/test-media"); Files.createDirectories(media);
        Path originalFile=media.resolve(prefix+".mp3");
        Files.write(originalFile,new byte[]{'I','D','3',4,0,0});
        try {
            var lesson=lessons.saveAndFlush(new Lesson(prefix,prefix,"Original",CefrLevel.A1,"QA",5,1,Instant.now()));
            for(int i=1;i<=2;i++) {
                var sentence=new LessonSentence(lesson,i,"Segment "+i,1.0);
                sentence.update("Segment "+i,1.0,(double)(i-1),(double)i);
                sentence.attachAudio("/media/"+originalFile.getFileName()); sentences.saveAndFlush(sentence);
            }
            var copied=json.readTree(mvc.perform(post("/api/admin/lessons/"+lesson.getId()+"/copy").with(user(admin)).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(false)).andReturn().getResponse().getContentAsString());
            long id=copied.path("id").asLong();
            String audio=copied.path("sentences").get(0).path("audioUrl").asText();
            assertThat(audio).isEqualTo("/media/"+originalFile.getFileName());
            assertThat(copied.path("sentences").get(1).path("audioUrl").asText()).isEqualTo(audio);
            Path copiedFile=media.resolve(audio.substring("/media/".length()));
            assertThat(Files.readAllBytes(copiedFile)).isEqualTo(Files.readAllBytes(originalFile));
            for(int i=0;i<2;i++) {
                mvc.perform(delete("/api/admin/lessons/"+id+"/sentences/"+copied.path("sentences").get(i).path("id").asLong()).with(user(admin)).with(csrf()))
                        .andExpect(status().isOk());
                assertThat(Files.exists(copiedFile)).isTrue();
                assertThat(Files.exists(originalFile)).isTrue();
            }
            for(var sentence:sentences.findByLessonIdOrderByPositionAsc(lesson.getId())) {
                mvc.perform(delete("/api/admin/lessons/"+lesson.getId()+"/sentences/"+sentence.getId()).with(user(admin)).with(csrf())).andExpect(status().isOk());
            }
            assertThat(Files.exists(originalFile)).isFalse();
        } finally { Files.deleteIfExists(originalFile); }
    }
}
