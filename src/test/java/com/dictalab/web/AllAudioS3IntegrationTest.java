package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"dictalab.media.s3.enabled=true", "dictalab.media.s3.all-audio=true",
        "dictalab.media.s3.bucket=audio-test", "dictalab.media.s3.region=us-east-1",
        "dictalab.conversation.enabled=true", "dictalab.media.s3.cleanup-enabled=false"})
@AutoConfigureMockMvc
class AllAudioS3IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired ConversationService conversation;
    @Autowired S3MediaLifecycle lifecycle;
    @Autowired ObjectMapper json;
    @MockitoBean S3Client s3;
    @MockitoBean S3Presigner signer;
    @MockitoBean ShopAiKeyClient ai;
    @MockitoBean CartesiaClient tts;

    @Test void legacyRoutesKeepAccessRulesAndNeverFallBackToLocalAudio() throws Exception {
        String filename=UUID.randomUUID()+".wav";
        try(var real=S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "secret"))).build()) {
            when(signer.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(i -> real.presignGetObject(i.getArgument(0,GetObjectPresignRequest.class)));
            for(String url: new String[]{"/audio/test-"+filename,"/media/test-"+filename,"/private-media/test-"+filename}) {
                db.update("INSERT INTO audio_s3_aliases VALUES(?,?,?,?)",url,"legacy/"+filename,"0".repeat(64),44);
                if(url.startsWith("/audio/")) mvc.perform(get(url)).andExpect(status().isTemporaryRedirect());
                else mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
                if(url.startsWith("/private-media/")) mvc.perform(get(url).with(user("learner").roles("USER")))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Location"));
                mvc.perform(get(url).with(user("admin").roles("ADMIN"))).andExpect(status().isTemporaryRedirect())
                        .andExpect(header().string("Cache-Control","no-store"))
                        .andExpect(header().string("Location",org.hamcrest.Matchers.containsString("legacy/"+filename)));
            }
            mvc.perform(get("/audio/missing.wav")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/audio/listening-samples-20261015/index.html")).andExpect(status().isOk());
        }
    }

    @Test void newConversationWritesS3ChecksOwnershipAndCleansAfterAccountDeletion() throws Exception {
        var owner=users.saveAndFlush(new AppUser("s3-owner-"+UUID.randomUUID()+"@test.local","Owner","unused",Instant.now()));
        var principal=DictaLabPrincipal.from(owner);
        String id=UUID.randomUUID().toString();
        var state=json.createObjectNode();state.put("completed",false);
        var turn=state.putArray("turns").addObject();turn.put("number",1);turn.put("plays",0);turn.put("done",false);
        turn.putArray("attempts");turn.putObject("prompt").put("utteranceEn","Please meet me at ten near the cafe tomorrow.");
        db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES(?,?,'cafe','A1',?)",id,owner.getId(),state.toString());
        byte[] wave=new byte[48];System.arraycopy("RIFF".getBytes(),0,wave,0,4);System.arraycopy("WAVE".getBytes(),0,wave,8,4);
        when(ai.configured()).thenReturn(true);when(tts.enabled()).thenReturn(true);when(tts.synthesize(anyString())).thenReturn(wave);
        conversation.play(owner.getId(),id,1,"original",false);
        assertThat(db.queryForObject("SELECT wav IS NULL FROM ai_conversation_audio WHERE session_id=?",Boolean.class,id)).isTrue();
        String url=db.queryForObject("SELECT s3_audio_url FROM ai_conversation_audio WHERE session_id=?",String.class,id);
        assertThat(url).startsWith("/conversation-media/s3/");
        verify(s3).putObject(argThat((PutObjectRequest p)->p.key().contains("conversation/")),any(software.amazon.awssdk.core.sync.RequestBody.class));
        String api="/api/v1/conversation/sessions/"+id+"/audio/1/original";
        try(var real=S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "secret"))).build()) {
            when(signer.presignGetObject(any(GetObjectPresignRequest.class))).thenAnswer(i -> real.presignGetObject(i.getArgument(0,GetObjectPresignRequest.class)));
            mvc.perform(get(api).with(user(principal))).andExpect(status().isTemporaryRedirect());
            var other=users.saveAndFlush(new AppUser("s3-other-"+UUID.randomUUID()+"@test.local","Other","unused",Instant.now()));
            mvc.perform(get(api).with(user(DictaLabPrincipal.from(other)))).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"));
            mvc.perform(get(url).with(user(principal))).andExpect(status().isForbidden());
        }
        lifecycle.cleanup();verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
        db.update("DELETE FROM app_users WHERE id=?",owner.getId());lifecycle.cleanup();
        assertThat(db.queryForObject("SELECT cleanup_after IS NOT NULL FROM s3_media_lifecycle WHERE audio_url=?",Boolean.class,url)).isTrue();
        db.update("UPDATE s3_media_lifecycle SET cleanup_after=CURRENT_TIMESTAMP-INTERVAL '1' DAY WHERE audio_url=?",url);
        lifecycle.cleanup();verify(s3).deleteObject(argThat((DeleteObjectRequest p)->p.key().contains("conversation/")));
        assertThat(db.queryForObject("SELECT status FROM s3_media_lifecycle WHERE audio_url=?",String.class,url)).isEqualTo("DELETED");
    }
}
