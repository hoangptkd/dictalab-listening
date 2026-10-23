package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationGenerationPromptTest {
    private final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void malformedSlotPlanRepairsOnlyWhenExplicitlyEnabled(boolean repairEnabled) throws Exception {
        var service = org.mockito.Mockito.mock(ConversationService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var local = org.mockito.Mockito.mock(ConversationLocalClient.class);
        var ai = org.mockito.Mockito.mock(GeminiClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "json", json);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "localGeneration", local);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ai", ai);
        if (repairEnabled) org.springframework.test.util.ReflectionTestUtils.setField(service, "generationRepairEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "budget", org.mockito.Mockito.mock(AiUsageBudget.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "config",
                new com.dictalab.config.ConversationProperties(true, "unit-model", null, null, null, null));
        var good = json.readTree("""
                {"slots":[{"kind":"vertical_transport","askVi":"Phương tiện lên tầng"},
                           {"kind":"receipt_location","askVi":"Nơi để biên nhận"}],
                 "utteranceEn":"Please use the lift beside reception. Your checkout receipt is in the green envelope.",
                 "facts":[{"textVi":"Thang máy","evidenceEn":"the lift"},
                          {"textVi":"Phong bì xanh lá","evidenceEn":"the green envelope"}],
                 "questionVi":"Bạn dùng gì để lên tầng và biên nhận ở đâu?",
                 "translationVi":"Xin dùng thang máy cạnh lễ tân. Biên nhận trả phòng ở trong phong bì xanh lá.",
                 "taskVi":"Nghe hướng dẫn và thông tin giấy tờ.","skill":"Nắm thông tin cụ thể"}
                """);
        var bad = (tools.jackson.databind.node.ObjectNode) good.deepCopy(); bad.withArray("facts").remove(1);
        org.mockito.Mockito.when(local.enabled()).thenReturn(true);
        org.mockito.Mockito.when(local.completeTask(org.mockito.ArgumentMatchers.eq("A1"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(
                        new ConversationLocalClient.GeneratedTask(bad), new ConversationLocalClient.GeneratedTask(good));
        org.mockito.Mockito.when(ai.completeJson(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("unit-model"), org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(json.readTree("{\"valid\":true,\"reason\":\"\"}"));
        var state = json.createObjectNode().put("curatedStarter", true);
        state.putArray("turns").addObject().set("prompt", ConversationContent.starter(json, "hotel", "A1"));
        var rowClass = Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor = rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var row = constructor.newInstance("synthetic", 0L, "hotel", "A1", state, 0L);
        if (!repairEnabled) {
            var error = assertThrows(IllegalStateException.class, () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                            "generatePrompt", row, System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos()));
            assertEquals("Slot/fact count mismatch", error.getMessage());
            org.mockito.Mockito.verify(local, org.mockito.Mockito.times(1)).completeTask(org.mockito.ArgumentMatchers.eq("A1"),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
            org.mockito.Mockito.verifyNoInteractions(ai);
            assertFalse(state.has("pendingGenerationSlots"));
            return;
        }
        tools.jackson.databind.JsonNode result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "generatePrompt", row, System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos());
        assertEquals(ConversationLocalClient.normalizeOutput(good), result);
        var inputs = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(local, org.mockito.Mockito.times(2)).completeTask(org.mockito.ArgumentMatchers.eq("A1"),
                inputs.capture(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        var repairedInput = json.readTree(inputs.getAllValues().get(1));
        assertEquals("Slot/fact count mismatch", repairedInput.path("validationError").asText());
        assertEquals(bad, repairedInput.path("previousInvalidPrompt"));
        assertEquals(good.path("slots"), state.path("pendingGenerationSlots"));
    }

    @Test void slotLabelsAreSavedOutsidePublicFactsAndUsedOnlyInGenerationHistory() throws Exception {
        var service = org.mockito.Mockito.mock(ConversationService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "json", json);
        var raw = json.readTree(ConversationGenerationPrompt.wireExample("A1", 2));
        var state = json.createObjectNode(); state.putArray("turns");
        state.set("pendingGenerationSlots", raw.path("slots"));
        var rowClass = Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor = rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var row = constructor.newInstance("synthetic", 0L, "hotel", "A1", state, 0L);
        var prompt = ConversationLocalClient.normalizeOutput(raw);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "addTurn", row, prompt);
        assertFalse(state.has("pendingGenerationSlots"));
        var turn = state.path("turns").path(0);
        assertEquals(raw.path("slots"), turn.path("generationSlots"));
        assertEquals("receptionist", turn.path("speaker").asText());
        tools.jackson.databind.JsonNode history = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "historyFacts", turn);
        assertEquals(raw.path("slots").path(0).path("askVi"), history.path(0).path("askVi"));
        assertFalse(prompt.path("facts").path(0).has("askVi"));
        assertFalse(prompt.has("slots"));
        tools.jackson.databind.JsonNode view = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "view", row);
        assertFalse(view.path("turns").path(0).has("generationSlots"));
        assertFalse(view.path("turns").path(0).path("prompt").has("facts"));
    }

    @Test void examplesMatchTheRequestedTurnAndStayGroundedAtEveryLevel() {
        for (String level : List.of("A1", "A2", "B1")) for (int turn : List.of(2, 3, 4)) {
            var input = ConversationGenerationPrompt.exampleInput(level, turn);
            assertEquals(turn, input.get("turnNumber"));
            var history = json.valueToTree(input.get("history"));
            assertEquals(turn == 4 ? 0 : turn - 1, history.size());
            assertEquals("workshop employee", input.get("partnerRole"));
            var output = json.readTree(ConversationGenerationPrompt.example(level, turn));
            String speech = output.path("utteranceEn").asText();
            assertEquals(level.equals("A1") ? 2 : 3, output.path("facts").size());
            output.path("facts").forEach(f -> assertTrue(speech.contains(f.path("evidenceEn").asText())));
            int words = speech.split("\\s+").length;
            var bounds = json.valueToTree(input.get("levelProfile"));
            assertTrue(words >= bounds.path("minWords").asInt() && words <= bounds.path("maxWords").asInt());
            if (turn == 4) assertFalse(ConversationGenerationPrompt.example(level, 2).equals(
                    ConversationGenerationPrompt.example(level, turn)));
        }
    }

    @Test void transferContextDropsStoryButRetainsValuesAndWeakSkillsIdempotently() {
        var input = json.readTree("""
                {"scenario":"Nhận hàng","difficulty":"A2","turnNumber":4,
                 "scenarioJourney":"OLD STORY","sessionBlueprint":{"branchId":"standard","journey":"OLD STORY"},
                 "history":[{"utteranceEn":"OLD STORY","questionVi":"OLD QUESTION","skill":"Nắm thông tin cụ thể",
                   "facts":[{"id":"f1","textVi":"Quầy","evidenceEn":"desk"}],
                   "evaluation":{"facts":[{"factId":"f1","status":"MISSED"}]}}]}
                """);
        var result = ConversationGenerationPrompt.context(json, input);
        assertTrue(result.path("history").isEmpty());
        assertFalse(result.toString().contains("OLD STORY"));
        assertFalse(result.toString().contains("OLD QUESTION"));
        assertEquals("Quầy", result.path("previouslyTestedDetails").path(0).path("textVi").asText());
        assertEquals("Nắm thông tin cụ thể", result.path("weakSkills").path(0).asText());
        assertEquals("parcel recipient", result.path("partnerRole").asText());
        assertEquals(result, ConversationGenerationPrompt.context(json, result));
        assertFalse(input.path("history").isEmpty());
        result.put("scenarioJourney", "STALE STORY");
        ((tools.jackson.databind.node.ObjectNode) result.path("previouslyTestedDetails").path(0)).put("evidenceEn", "STALE STORY");
        assertFalse(ConversationGenerationPrompt.context(json, result).toString().contains("STALE STORY"));
    }

    @Test void continuationKeepsFullHistoryAndAuthoredRolesCoverEveryBranch() {
        var input = json.readTree("""
                {"scenario":"Gọi món ở quán","turnNumber":2,"history":[{"utteranceEn":"Existing order",
                 "questionVi":"Khách gọi gì?","facts":[{"textVi":"Trà","evidenceEn":"tea"}]}]}
                """);
        var result = ConversationGenerationPrompt.context(json, input);
        assertEquals(input.path("history"), result.path("history"));
        assertEquals("unknown", result.path("encounterState").path("sourceTurns").path(0).path("speaker").asText());
        assertTrue(result.path("encounterState").path("currentObject").isNull());
        assertEquals("customer", result.path("partnerRole").asText());
        for (var scene : ConversationContent.SCENES) for (var branch : ConversationContent.branches(scene.id())) {
            var roles = ConversationContent.roles(scene.id(), branch.id());
            assertFalse(roles.get("partnerRole").equals("scenario partner"));
            assertFalse(roles.get("learnerRole").isBlank());
        }
        assertEquals("server", ConversationContent.roles("cafe", "payment").get("partnerRole"));
        assertEquals("guest", ConversationContent.roles("hotel", "quiet").get("partnerRole"));
    }

    @Test void eachLevelGetsOnlyItsGroundedExample() {
        for (String level : List.of("A1", "A2", "B1")) {
            var example = json.readTree(ConversationGenerationPrompt.example(level));
            String speech = example.path("utteranceEn").asText();
            var bounds = switch (level) {
                case "A1" -> Map.of("minWords", 8, "maxWords", 35);
                case "A2" -> Map.of("minWords", 18, "maxWords", 55);
                default -> Map.of("minWords", 30, "maxWords", 90);
            };
            assertTrue(speech.split("\\s+").length >= bounds.get("minWords"));
            assertTrue(speech.split("\\s+").length <= bounds.get("maxWords"));
            assertEquals(level.equals("A1") ? 2 : 3, example.path("facts").size());
            assertTrue(ConversationContent.SKILLS.containsValue(example.path("skill").asText()));
            assertEquals(level, ConversationGenerationPrompt.exampleInput(level).get("difficulty"));
            example.path("facts").forEach(f -> assertTrue(speech.contains(f.path("evidenceEn").asText())));
            String instruction = ConversationGenerationPrompt.instruction(level);
            assertTrue(instruction.contains(ConversationGenerationPrompt.wireExample(level, 2)));
            for (String other : List.of("A1", "A2", "B1")) if (!level.equals(other))
                assertFalse(instruction.contains(ConversationGenerationPrompt.wireExample(other, 2)));
        }
    }

    @Test void contractDefinesInputsAtomicSlotsAndUnambiguousProgression() {
        String instruction = ConversationGenerationPrompt.instruction("A1");
        for (String field : List.of("scenario", "difficulty", "turnNumber", "levelProfile", "listeningSkills",
                "scenarioJourney", "history", "practiceFocus", "sessionBlueprint", "recentPractice",
                "validationError", "previousInvalidPrompt")) assertTrue(instruction.contains(field));
        assertTrue(instruction.contains("One fact = exactly one slot"));
        assertTrue(instruction.contains("Turn 4 is a NEW encounter within the SAME scenario category"));
        assertTrue(instruction.contains("Both taskVi and questionVi are shown BEFORE listening"));
        assertFalse(instruction.contains("Count utteranceEn words before returning"));
        assertThrows(IllegalArgumentException.class, () -> ConversationGenerationPrompt.instruction("B2"));
    }

    @Test void copiedFewShotOutputCannotBePublishedAsANewTask() throws Exception {
        var service=org.mockito.Mockito.mock(ConversationService.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"json",json);
        var rowClass=Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor=rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        for (String level : List.of("A1","A2","B1")) {
            var state=json.createObjectNode().put("curatedStarter",true); state.putArray("turns");
            var row=constructor.newInstance("synthetic",0L,"hotel",level,state,0L);
            var failure=assertThrows(IllegalStateException.class, () -> org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(service,"validateNextPrompt",row,json.readTree(ConversationGenerationPrompt.example(level))));
            assertEquals("Copied generation example",failure.getMessage());
        }
    }
}
