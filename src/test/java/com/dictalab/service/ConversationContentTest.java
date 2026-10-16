package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConversationContentTest {
    @Test void everyBranchHasGroundedBilingualOpeningsAtEveryLevel() {
        var json = new ObjectMapper();
        int branches = 0;
        for (var scene : ConversationContent.SCENES) {
            var variants = ConversationContent.branches(scene.id());
            assertThat(variants).hasSize(Set.of("cafe", "hotel", "directions").contains(scene.id()) ? 8 : 2);
            assertThat(variants).extracting(ConversationContent.Branch::id).doesNotHaveDuplicates();
            branches += variants.size();
            for (String level : List.of("A1", "A2", "B1")) {
                var utterances = new HashSet<String>();
                for (var branch : variants) {
                    var prompt = ConversationContent.starter(json, branch.opening(), level);
                    String english = prompt.path("utteranceEn").asText();
                    assertThat(utterances.add(english)).isTrue();
                    assertThat(english.split("\\s+").length).isBetween(8, 55);
                    assertThat(prompt.path("facts").size()).isEqualTo(level.equals("A1") ? 2 : 3);
                    assertThat(prompt.path("translationVi").asText()).isNotBlank();
                    assertThat(branch.journey()).contains("SAME encounter", "NEW encounter");
                    prompt.path("facts").forEach(f -> {
                        assertThat(f.path("textVi").asText()).isNotBlank();
                        assertThat(english).contains(f.path("evidenceEn").asText());
                    });
                }
            }
        }
        assertThat(branches).isEqualTo(66);
    }
}
