package com.dictalab.web;

import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminContentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private LessonSentenceRepository sentenceRepository;

    @Test
    void adminEndpointsRequireAuthenticationAndAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/lessons"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/lessons")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("learner@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminMutationsStillRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/admin/lessons")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLessonJson("csrf-required")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsUploadsAndPublishesLessonWithoutRebuildingApplication() throws Exception {
        String slug = "admin-upload-" + UUID.randomUUID();
        mockMvc.perform(post("/api/admin/lessons")
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLessonJson(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false));

        Lesson lesson = lessonRepository.findBySlug(slug).orElseThrow();
        mockMvc.perform(post("/api/admin/lessons/{lessonId}/sentences", lesson.getId())
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transcript": "Could I have a cup of tea, please?",
                                  "speechRate": 1.0,
                                  "audioStartSeconds": null,
                                  "audioEndSeconds": null
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sentences.length()").value(1));

        LessonSentence sentence = sentenceRepository.findByLessonIdOrderByPositionAsc(lesson.getId()).get(0);
        byte[] validMp3Header = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3};
        MockMultipartFile audio = new MockMultipartFile(
                "file", "real-speaker.mp3", "audio/mpeg", validMp3Header
        );
        mockMvc.perform(multipart("/api/admin/lessons/{lessonId}/sentences/{sentenceId}/audio",
                        lesson.getId(), sentence.getId())
                        .file(audio)
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentences[0].audioUrl").value(org.hamcrest.Matchers.startsWith("/media/")));

        sentence = sentenceRepository.findById(sentence.getId()).orElseThrow();
        assertThat(Files.exists(Path.of("target/test-media")
                .resolve(sentence.getAudioUrl().substring("/media/".length())))).isTrue();

        mockMvc.perform(put("/api/admin/lessons/{lessonId}/publication", lesson.getId())
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        mockMvc.perform(get("/api/v1/catalog/lessons?level=A1")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == '" + slug + "')]").exists());
    }

    @Test
    void disguisedNonAudioFileIsRejected() throws Exception {
        String slug = "invalid-audio-" + UUID.randomUUID();
        mockMvc.perform(post("/api/admin/lessons")
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLessonJson(slug)))
                .andExpect(status().isCreated());
        Lesson lesson = lessonRepository.findBySlug(slug).orElseThrow();
        mockMvc.perform(post("/api/admin/lessons/{lessonId}/sentences", lesson.getId())
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transcript": "This is not an audio file.",
                                  "speechRate": 1.0,
                                  "audioStartSeconds": null,
                                  "audioEndSeconds": null
                                }
                                """))
                .andExpect(status().isCreated());
        LessonSentence sentence = sentenceRepository.findByLessonIdOrderByPositionAsc(lesson.getId()).get(0);
        MockMultipartFile fakeAudio = new MockMultipartFile(
                "file", "not-really.mp3", "audio/mpeg", "plain text".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/lessons/{lessonId}/sentences/{sentenceId}/audio",
                        lesson.getId(), sentence.getId())
                        .file(fakeAudio)
                        .with(csrf())
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File tải lên không phải audio MP3, M4A hoặc WAV hợp lệ."));
    }

    private String validLessonJson(String slug) {
        return """
                {
                  "slug": "%s",
                  "title": "Ordering tea",
                  "description": "A short real-voice listening lesson.",
                  "level": "A1",
                  "topic": "Cafe",
                  "estimatedMinutes": 5,
                  "displayOrder": 900
                }
                """.formatted(slug);
    }
}
