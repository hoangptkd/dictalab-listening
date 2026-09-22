package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.repository.LessonRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.CatalogService;
import com.dictalab.service.PersonalContentImportService;
import com.dictalab.service.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "dictalab.personal-content.enabled=true",
        "dictalab.personal-content.root=src/test/resources/personal-content",
        "dictalab.personal-content.manifest=src/test/resources/personal-content/manifest.json"
})
@AutoConfigureMockMvc
class PersonalContentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PersonalContentImportService personalContentImportService;

    private AppUser regularUser;
    private AppUser administrator;
    private Lesson personalLesson;

    @BeforeEach
    void setUp() {
        regularUser = userRepository.save(new AppUser(
                "personal-user-" + UUID.randomUUID() + "@dictalab.local", "Learner", "unused", Instant.now()
        ));
        administrator = new AppUser(
                "personal-admin-" + UUID.randomUUID() + "@dictalab.local", "Administrator", "unused", Instant.now()
        );
        administrator.promoteToAdmin(Instant.now());
        administrator = userRepository.save(administrator);
        personalLesson = lessonRepository.findBySlug("british-council-personal-a2-test-v2").orElseThrow();
    }

    @Test
    void onlyAdministratorCanDiscoverAndStartPersonalLesson() {
        assertThat(catalogService.lessons(regularUser.getId(), CefrLevel.A2, false))
                .noneMatch(item -> item.id().equals(personalLesson.getId()));
        ApiDtos.LessonSummary summary = catalogService.lessons(administrator.getId(), CefrLevel.A2, true).stream()
                .filter(item -> item.id().equals(personalLesson.getId())).findFirst().orElseThrow();
        assertThat(summary.previewAudioUrl()).isEqualTo("/private-media/A2/test-audio.mp3");
        assertThat(summary.previewWords()).extracting(ApiDtos.PreviewWord::word)
                .containsExactly("private", "listening");

        assertThatThrownBy(() -> practiceService.start(
                regularUser.getId(), new ApiDtos.StartSessionRequest(personalLesson.getId())
        )).isInstanceOf(ResourceNotFoundException.class);

        assertThat(practiceService.start(
                administrator.getId(), new ApiDtos.StartSessionRequest(personalLesson.getId())
        ).sentence().audioUrl()).isEqualTo("/private-media/A2/test-audio.mp3");
    }

    @Test
    void privateMediaRequiresAdministratorRole() throws Exception {
        mockMvc.perform(get("/private-media/A2/test-audio.mp3"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/private-media/A2/test-audio.mp3")
                        .with(user(DictaLabPrincipal.from(regularUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/private-media/A2/test-audio.mp3")
                        .with(user(DictaLabPrincipal.from(administrator))))
                .andExpect(status().isOk());
    }

    @Test
    void importingCurrentRevisionHidesOlderPersonalLessonsWithoutDeletingThem() {
        Lesson olderLesson = new Lesson(
                "british-council-personal-a2-legacy-" + UUID.randomUUID(), "Old content", "Old content.",
                CefrLevel.A2, "British Council", 1, 998, Lesson.PERSONAL_CONTENT_ORIGIN, Instant.now()
        );
        olderLesson.setPublished(true, Instant.now());
        olderLesson = lessonRepository.save(olderLesson);

        personalContentImportService.importConfiguredManifest();

        assertThat(lessonRepository.findById(olderLesson.getId()).orElseThrow().isActive()).isFalse();
        assertThat(lessonRepository.findById(olderLesson.getId())).isPresent();
    }
}
