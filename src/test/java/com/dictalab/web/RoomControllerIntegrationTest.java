package com.dictalab.web;

import com.dictalab.repository.AppUserRepository;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.domain.AppUser;
import com.dictalab.room.CallRoom;
import com.dictalab.room.RoomRegistry;
import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RoomControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomRegistry registry;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private PracticeSessionRepository sessionRepository;

    private DictaLabPrincipal hostPrincipal;
    private DictaLabPrincipal guestPrincipal;
    private DictaLabPrincipal thirdPrincipal;

    @BeforeEach
    void setUp() {
        // Tạo mock principals với userId phân biệt để tránh conflict giữa các test
        // Dùng ID lớn để không trùng với dữ liệu seed
        hostPrincipal = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "host-" + UUID.randomUUID() + "@test.local", "Chủ phòng", "hash", Instant.now())));
        guestPrincipal = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "guest-" + UUID.randomUUID() + "@test.local", "Khách mời", "hash", Instant.now())));
        thirdPrincipal = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "third-" + UUID.randomUUID() + "@test.local", "Người thứ ba", "hash", Instant.now())));
    }

    @Test
    void sharedLessonKeepsSessionsPrivateAndRoomCanBeRecreated() throws Exception {
        AppUser host = userRepository.save(new AppUser("shared-host-" + UUID.randomUUID() + "@test.local",
                "Chủ phòng", "unused", Instant.now()));
        AppUser guest = userRepository.save(new AppUser("shared-guest-" + UUID.randomUUID() + "@test.local",
                "Bạn học", "unused", Instant.now()));
        DictaLabPrincipal owner = new DictaLabPrincipal(host.getId(), host.getEmail(), host.getDisplayName(),
                "unused", "USER", true);
        DictaLabPrincipal partner = new DictaLabPrincipal(guest.getId(), guest.getEmail(), guest.getDisplayName(),
                "unused", "USER", true);
        String roomId = extractField(mockMvc.perform(post("/api/v1/rooms")
                .with(csrf()).with(user(owner))).andExpect(status().isCreated()).andReturn(), "roomId");
        try {
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(partner))).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/rooms/" + roomId).with(user(thirdPrincipal)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/rooms/" + roomId).with(csrf()).with(user(partner)))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(partner))).andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/approve")
                    .with(csrf()).with(user(owner))).andExpect(status().isOk());

            Long lessonId = lessonRepository.findByActiveTrueOrderByLevelAscDisplayOrderAsc().get(0).getId();
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/lesson")
                    .with(csrf()).with(user(partner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lessonId\":" + lessonId + "}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/lesson")
                    .with(csrf()).with(user(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lessonId\":" + lessonId + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.lessonPosition").value(1));

            String hostSession = extractField(mockMvc.perform(post("/api/v1/rooms/" + roomId + "/practice")
                    .with(csrf()).with(user(owner))).andExpect(status().isOk()).andReturn(), "sessionId");
            String guestSession = extractField(mockMvc.perform(post("/api/v1/rooms/" + roomId + "/practice")
                    .with(csrf()).with(user(partner))).andExpect(status().isOk()).andReturn(), "sessionId");
            assertThat(hostSession).isNotEqualTo(guestSession);
            assertThat(sessionRepository.findById(hostSession).orElseThrow().getRoomId()).isEqualTo(roomId);
            assertThat(sessionRepository.findById(guestSession).orElseThrow().isFirstExposure()).isFalse();
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/ready")
                    .with(csrf()).with(user(owner))).andExpect(status().isConflict());
            assertThat(registry.updateDraft(roomId, host.getId(), 1, 1, List.of("hello"))).isTrue();
            assertThat(registry.updateDraft(roomId, host.getId(), 1, 1, List.of("stale"))).isFalse();
            assertThat(registry.updateDraft(roomId, thirdPrincipal.id(), 1, 1, List.of("secret"))).isFalse();
            mockMvc.perform(get("/api/v1/rooms/" + roomId).with(user(partner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.peerDrafts[0]").value("hello"));

            mockMvc.perform(delete("/api/v1/rooms/" + roomId).with(csrf()).with(user(partner)))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/rooms/mine").with(user(owner)))
                    .andExpect(status().isNoContent());
            String secondRoom = extractField(mockMvc.perform(post("/api/v1/rooms")
                    .with(csrf()).with(user(owner))).andExpect(status().isCreated()).andReturn(), "roomId");
            registry.leaveRoom(secondRoom, owner.id());
        } finally {
            registry.leaveRoom(roomId, owner.id());
        }
    }

    // ----------------------------------------------------------------------- 1. Tạo phòng

    @Test
    void createRoom_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/rooms").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRoom_returnsRoomAndInviteCode() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf())
                        .with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").isString())
                .andExpect(jsonPath("$.inviteCode").isString())
                .andExpect(jsonPath("$.inviteUrl").isString())
                .andReturn();

        // Dọn dẹp
        String roomId = extractField(result, "roomId");
        String inviteCode = extractField(result, "inviteCode");
        assertThat(extractField(result, "inviteUrl")).endsWith("/rooms/join/" + inviteCode);
        registry.leaveRoom(roomId, hostPrincipal.id());
    }

    // ----------------------------------------------------------------------- 2. Join phòng

    @Test
    void joinRoom_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/rooms/fake-room-id/join").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void joinRoom_asHost_returnsBadRequest() throws Exception {
        // Host tạo phòng
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            // Host cố join chính phòng mình
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                            .with(csrf()).with(user(hostPrincipal)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Bạn là chủ phòng này."));
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    @Test
    void joinRoom_addsGuestAsPending() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                            .with(csrf()).with(user(guestPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

            // Kiểm tra trạng thái phòng
            CallRoom room = registry.findById(roomId).orElseThrow();
            assertThat(room.getPendingUserId()).isEqualTo(guestPrincipal.id());
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 3. Approve / Reject

    @Test
    void approveGuest_onlyByHost() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            // Guest xin vào
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(guestPrincipal))).andExpect(status().isOk());

            // Người thứ ba cố approve → 403
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/approve")
                            .with(csrf()).with(user(thirdPrincipal)))
                    .andExpect(status().isForbidden());

            // Host approve → thành công
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/approve")
                            .with(csrf()).with(user(hostPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    @Test
    void rejectGuest_onlyByHost() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(guestPrincipal))).andExpect(status().isOk());

            // Người thứ ba cố reject → 403
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/reject")
                            .with(csrf()).with(user(thirdPrincipal)))
                    .andExpect(status().isForbidden());

            // Host reject → thành công
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/reject")
                            .with(csrf()).with(user(hostPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("WAITING_GUEST"));
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 4. Giới hạn hai người

    @Test
    void joinRoom_whenFull_returns409() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            // Guest join và được approve
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(guestPrincipal))).andExpect(status().isOk());
            registry.approveGuest(roomId, hostPrincipal.id());

            // Người thứ ba cố join → 409 FULL
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                            .with(csrf()).with(user(thirdPrincipal)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Phòng đã đủ 2 người."));
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 5. Rời phòng

    @Test
    void leaveRoom_byHost_closesRoom() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        mockMvc.perform(delete("/api/v1/rooms/" + roomId)
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isNoContent());

        CallRoom room = registry.findById(roomId).orElse(null);
        assertThat(room).isNotNull();
        assertThat(room.isClosed()).isTrue();
    }

    @Test
    void leaveRoom_byGuest_removesGuestKeepsRoom() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                    .with(csrf()).with(user(guestPrincipal))).andExpect(status().isOk());
            registry.approveGuest(roomId, hostPrincipal.id());

            mockMvc.perform(delete("/api/v1/rooms/" + roomId)
                            .with(csrf()).with(user(guestPrincipal)))
                    .andExpect(status().isNoContent());

            CallRoom room = registry.findById(roomId).orElseThrow();
            assertThat(room.isClosed()).isFalse();
            assertThat(room.getGuestUserId()).isNull();
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 6. ICE config

    @Test
    void iceConfig_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/fake-id/ice-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void iceConfig_onlyForApprovedMember() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        try {
            // Host (thành viên) được lấy ICE config
            mockMvc.perform(get("/api/v1/rooms/" + roomId + "/ice-config")
                            .with(user(hostPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iceServers").isArray());

            // Người ngoài không được
            mockMvc.perform(get("/api/v1/rooms/" + roomId + "/ice-config")
                            .with(user(thirdPrincipal)))
                    .andExpect(status().isForbidden());
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 7. Cùng user không thể vào hai phòng

    @Test
    void sameUserCannotJoinTwoRooms() throws Exception {
        // Host tạo phòng 1
        MvcResult created1 = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId1 = extractField(created1, "roomId");

        // Host (đang trong phòng) cố tạo phòng 2 → 409
        try {
            mockMvc.perform(post("/api/v1/rooms")
                            .with(csrf()).with(user(hostPrincipal)))
                    .andExpect(status().isConflict());
        } finally {
            registry.leaveRoom(roomId1, hostPrincipal.id());
        }
    }

    @Test
    void sameGuest_cannotJoinTwoRooms() throws Exception {
        // Host A tạo phòng 1
        MvcResult created1 = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId1 = extractField(created1, "roomId");

        // Host B tạo phòng 2
        MvcResult created2 = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(thirdPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId2 = extractField(created2, "roomId");

        try {
            // Guest join phòng 1 và được approve
            mockMvc.perform(post("/api/v1/rooms/" + roomId1 + "/join")
                    .with(csrf()).with(user(guestPrincipal))).andExpect(status().isOk());
            registry.approveGuest(roomId1, hostPrincipal.id());

            // Guest cố join phòng 2 → 409
            mockMvc.perform(post("/api/v1/rooms/" + roomId2 + "/join")
                            .with(csrf()).with(user(guestPrincipal)))
                    .andExpect(status().isConflict());
        } finally {
            registry.leaveRoom(roomId1, hostPrincipal.id());
            registry.leaveRoom(roomId2, thirdPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- 8. Concurrent join — chỉ một thành công

    @Test
    void concurrentJoin_onlyOneSucceeds() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/rooms")
                        .with(csrf()).with(user(hostPrincipal)))
                .andExpect(status().isCreated())
                .andReturn();
        String roomId = extractField(created, "roomId");

        // Tạo nhiều principals để simulate concurrent join
        DictaLabPrincipal p1 = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "c1-" + UUID.randomUUID() + "@t.local", "C1", "h", Instant.now())));
        DictaLabPrincipal p2 = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "c2-" + UUID.randomUUID() + "@t.local", "C2", "h", Instant.now())));
        DictaLabPrincipal p3 = DictaLabPrincipal.from(userRepository.save(new AppUser(
                "c3-" + UUID.randomUUID() + "@t.local", "C3", "h", Instant.now())));

        try {
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(3);
            ExecutorService executor = Executors.newFixedThreadPool(3);

            for (DictaLabPrincipal p : new DictaLabPrincipal[]{p1, p2, p3}) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        var response = mockMvc.perform(post("/api/v1/rooms/" + roomId + "/join")
                                .with(csrf()).with(user(p)));
                        int status = response.andReturn().getResponse().getStatus();
                        if (status == 200) successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            latch.countDown();
            done.await();
            executor.shutdown();

            // Chỉ đúng một request được accept (pending chỉ có 1 slot)
            assertThat(successCount.get()).isEqualTo(1);

            CallRoom room = registry.findById(roomId).orElseThrow();
            assertThat(room.getPendingUserId()).isNotNull();
        } finally {
            registry.leaveRoom(roomId, hostPrincipal.id());
        }
    }

    // ----------------------------------------------------------------------- helper

    private String extractField(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        // Trích field đơn giản không cần ObjectMapper dependency
        int start = json.indexOf("\"" + field + "\":\"") + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
