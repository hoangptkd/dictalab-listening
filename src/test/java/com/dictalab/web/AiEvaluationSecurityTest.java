package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AiEvaluationStudio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AiEvaluationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @MockitoBean AiEvaluationStudio studio;

    @Test void adminOnlyAndCsrfRequiredForAllMutations() throws Exception {
        var member=users.save(new AppUser("evaluation-member-"+UUID.randomUUID()+"@dictalab.local","Evaluation fixture","unused",Instant.now()));
        var admin=new AppUser("evaluation-admin-"+UUID.randomUUID()+"@dictalab.local","Evaluation fixture","unused",Instant.now());
        admin.promoteToAdmin(Instant.now());admin=users.save(admin);
        var principal=DictaLabPrincipal.from(admin);var memberPrincipal=DictaLabPrincipal.from(member);
        var value=new ObjectMapper().createObjectNode().put("local",true);
        when(studio.snapshot()).thenReturn(value);
        when(studio.createVersion(any(),eq(admin.getId()))).thenReturn(value);
        mvc.perform(get("/api/admin/ai-evaluation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ai-evaluation").with(user(memberPrincipal))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ai-evaluation").with(user(memberPrincipal))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ai-evaluation").with(user(principal))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.local").value(true));
        mvc.perform(get("/admin/ai-evaluation").with(user(principal))).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/ai-evaluation.html"));
        for(String path:new String[]{"/versions","/runs","/runs/test/stop"}) {
            mvc.perform(post("/api/admin/ai-evaluation"+path).with(user(principal)).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/ai-evaluation"+path).with(user(memberPrincipal)).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/ai-evaluation/versions").with(user(principal)).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(studio).createVersion(any(),eq(admin.getId()));
    }
}
