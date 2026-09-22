package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.domain.ReplayEventType;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(statements = "UPDATE lessons SET active = TRUE WHERE id IN (1, 2)")
class HistoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PracticeService practiceService;

    private AppUser userA;
    private AppUser userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.save(new AppUser(
                "history-user-a-" + UUID.randomUUID() + "@dictalab.local",
                "User A", "unused", Instant.now()
        ));
        userB = userRepository.save(new AppUser(
                "history-user-b-" + UUID.randomUUID() + "@dictalab.local",
                "User B", "unused", Instant.now()
        ));
    }

    @Test
    void unauthenticatedUserCannotAccessHistory() throws Exception {
        mockMvc.perform(get("/api/v1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Vui lòng đăng nhập để tiếp tục."));

        mockMvc.perform(get("/api/v1/history/any-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyHistoryForUserWithoutCompletedSessions() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                        .with(user(DictaLabPrincipal.from(userA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void completeSessionFlowAppearsInHistoryListAndDetail() throws Exception {
        String sessionId = completeLesson1(userA.getId());

        // 1. Check history list for userA
        mockMvc.perform(get("/api/v1/history")
                        .with(user(DictaLabPrincipal.from(userA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$[0].lessonId").value(1))
                .andExpect(jsonPath("$[0].lessonTitle").value("Morning at the Market"))
                .andExpect(jsonPath("$[0].level").value("A1"))
                .andExpect(jsonPath("$[0].overallScore").isNumber())
                .andExpect(jsonPath("$[0].firstExposure").value(true))
                .andExpect(jsonPath("$[0].completedAt").isNotEmpty());

        // 2. Check history detail for userA
        mockMvc.perform(get("/api/v1/history/" + sessionId)
                        .with(user(DictaLabPrincipal.from(userA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.lessonId").value(1))
                .andExpect(jsonPath("$.lessonTitle").value("Morning at the Market"))
                .andExpect(jsonPath("$.level").value("A1"))
                .andExpect(jsonPath("$.review.totalSentences").value(3))
                .andExpect(jsonPath("$.review.sentences", hasSize(3)))
                .andExpect(jsonPath("$.assessment").isNotEmpty());
    }

    @Test
    void userIsolationEnsuresUsersOnlySeeTheirOwnSessions() throws Exception {
        String userASessionId = completeLesson1(userA.getId());

        // User B sees empty history list
        mockMvc.perform(get("/api/v1/history")
                        .with(user(DictaLabPrincipal.from(userB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // User B cannot access User A's session detail
        mockMvc.perform(get("/api/v1/history/" + userASessionId)
                        .with(user(DictaLabPrincipal.from(userB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy phiên luyện tập."));
    }

    @Test
    void inProgressSessionCannotBeAccessedViaHistoryDetail() throws Exception {
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userA.getId(), new ApiDtos.StartSessionRequest(2L)
        );

        mockMvc.perform(get("/api/v1/history/" + session.sessionId())
                        .with(user(DictaLabPrincipal.from(userA))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Phiên luyện tập này chưa hoàn thành."));
    }

    @Test
    void nonExistentSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/history/non-existent-session-id")
                        .with(user(DictaLabPrincipal.from(userA))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy phiên luyện tập."));
    }

    private String completeLesson1(Long learnerId) {
        ApiDtos.PracticeStartResponse startResponse = practiceService.start(
                learnerId, new ApiDtos.StartSessionRequest(1L)
        );
        String sessionId = startResponse.sessionId();

        // Sentence 1: "Anna buys fresh apples at the market."
        listen(learnerId, sessionId, startResponse.sentence().sentenceId());
        answerAllWords(learnerId, sessionId, startResponse.sentence().sentenceId(),
                List.of("Anna", "buys", "fresh", "apples", "at", "the", "market"));
        ApiDtos.CompleteSentenceResponse s1Complete = practiceService.completeSentence(
                learnerId, sessionId, startResponse.sentence().sentenceId(),
                new ApiDtos.CompleteSentenceRequest(10)
        );
        assertThat(s1Complete.lessonCompleted()).isFalse();

        // Sentence 2: "She talks to a friendly fruit seller."
        ApiDtos.SentenceState s2 = s1Complete.nextSentence();
        listen(learnerId, sessionId, s2.sentenceId());
        answerAllWords(learnerId, sessionId, s2.sentenceId(),
                List.of("She", "talks", "to", "a", "friendly", "fruit", "seller"));
        ApiDtos.CompleteSentenceResponse s2Complete = practiceService.completeSentence(
                learnerId, sessionId, s2.sentenceId(),
                new ApiDtos.CompleteSentenceRequest(15)
        );
        assertThat(s2Complete.lessonCompleted()).isFalse();

        // Sentence 3: "Then she walks home before lunch."
        ApiDtos.SentenceState s3 = s2Complete.nextSentence();
        listen(learnerId, sessionId, s3.sentenceId());
        answerAllWords(learnerId, sessionId, s3.sentenceId(),
                List.of("Then", "she", "walks", "home", "before", "lunch"));
        ApiDtos.CompleteSentenceResponse s3Complete = practiceService.completeSentence(
                learnerId, sessionId, s3.sentenceId(),
                new ApiDtos.CompleteSentenceRequest(12)
        );
        assertThat(s3Complete.lessonCompleted()).isTrue();
        assertThat(s3Complete.result()).isNotNull();

        return sessionId;
    }

    private void listen(Long learnerId, String sessionId, Long sentenceId) {
        practiceService.replay(learnerId, sessionId, sentenceId, new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
        practiceService.replay(learnerId, sessionId, sentenceId, new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED));
    }

    private void answerAllWords(Long learnerId, String sessionId, Long sentenceId, List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            practiceService.checkWord(learnerId, sessionId, sentenceId, i, new ApiDtos.CheckWordRequest(words.get(i)));
        }
    }
}
