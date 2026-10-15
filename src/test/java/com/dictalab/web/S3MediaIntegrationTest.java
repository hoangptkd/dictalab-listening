package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.security.RedisTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.dictalab.service.AdminContentService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "dictalab.media.s3.enabled=true", "dictalab.media.s3.bucket=audio-test",
        "dictalab.media.s3.region=ap-southeast-1", "dictalab.media.root=target/s3-audio/test-media",
        "dictalab.media.s3.cleanup-enabled=true"
})
@AutoConfigureMockMvc
class S3MediaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AdminContentService adminContent;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.dictalab.service.S3MediaLifecycle lifecycle;
    @MockitoBean S3Client s3;
    @MockitoBean S3Presigner signer;
    @MockitoBean RedisTokenStore tokenStore;

    @Test void uploadsPlaysReplacesAndCleansOnlyUnreferencedAudioAfterCommitOrRollback() throws Exception {
        String email = "s3-admin-" + UUID.randomUUID() + "@test.local";
        db.update("INSERT INTO app_users(email,display_name,password_hash,role,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                email, "S3 test admin", "test-only", "ADMIN");
        long userId = db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        var admin = new DictaLabPrincipal(userId, email, "S3 test admin", "test-only", "ADMIN", true);
        var lesson = json.readTree(mvc.perform(post("/api/admin/lessons").with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"slug":"s3-%s","title":"S3 audio","description":"Storage test","level":"A1",
                         "topic":"Daily life","estimatedMinutes":3,"displayOrder":99}
                        """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long lessonId = lesson.path("id").asLong();
        var sentences = json.readTree(mvc.perform(post("/api/admin/lessons/" + lessonId + "/sentences")
                .with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\":\"A cup of tea.\",\"speechRate\":1.0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long sentenceId = sentences.path("sentences").path(0).path("id").asLong();
        String endpoint = "/api/admin/lessons/" + lessonId + "/sentences/" + sentenceId + "/audio";
        var file = new MockMultipartFile("file", "clip.mp3", "audio/mpeg", new byte[]{'I', 'D', '3', 4});
        mvc.perform(multipart(endpoint).file(file).with(user(admin))).andExpect(status().isForbidden());
        mvc.perform(multipart(endpoint).file(file).with(csrf()).with(user("learner").roles("USER")))
                .andExpect(status().isForbidden());
        then(s3).shouldHaveNoInteractions();

        var uploaded = json.readTree(mvc.perform(multipart(endpoint).file(file).with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String url = uploaded.path("sentences").path(0).path("audioUrl").asText();
        assertThat(url).startsWith("/media/s3/");
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?", String.class, sentenceId)).isEqualTo(url);
        then(s3).should().putObject(any(PutObjectRequest.class), any(RequestBody.class));
        try (var files = Files.list(Path.of("target/s3-audio/test-media"))) { assertThat(files).isEmpty(); }

        try (var realSigner = S3Presigner.builder().region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"))).build()) {
            given(signer.presignGetObject(any(GetObjectPresignRequest.class)))
                    .willAnswer(inv -> realSigner.presignGetObject(inv.getArgument(0, GetObjectPresignRequest.class)));
            mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
            mvc.perform(get(url).with(user(admin))).andExpect(status().isTemporaryRedirect())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("X-Amz-Signature=")));
        }
        // S3 errors do not replace the database reference or delete the previous object.
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willThrow(SdkClientException.create("S3 unavailable"));
        mvc.perform(multipart(endpoint).file(file).with(user(admin)).with(csrf())).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?", String.class, sentenceId)).isEqualTo(url);
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build());

        // Upload I/O is outside a transaction; a failed attachment retains durable cleanup intent.
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            db.update("UPDATE lessons SET active=TRUE WHERE id=?", lessonId);
            return software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build();
        });
        assertThatThrownBy(() -> adminContent.uploadAudio(lessonId, sentenceId, file))
                .isInstanceOf(com.dictalab.exception.ContentManagementException.class);
        db.update("UPDATE lessons SET active=FALSE WHERE id=?", lessonId);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM s3_media_lifecycle WHERE status='STAGED' AND cleanup_after IS NOT NULL", Integer.class)).isGreaterThanOrEqualTo(2);
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build());
        // Rollback of a deletion must not schedule the still-referenced object for cleanup.
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            adminContent.deleteSentence(lessonId, sentenceId);
            transaction.setRollbackOnly();
        });
        assertThat(db.queryForObject("SELECT cleanup_after FROM s3_media_lifecycle WHERE audio_url=?", java.sql.Timestamp.class, url)).isNull();
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?", String.class, sentenceId)).isEqualTo(url);
        clearInvocations(s3);

        // Copying a lesson shares the audio identity. Replacement must not break the copy.
        var copy = adminContent.copyLesson(lessonId);
        long copiedSentence = copy.sentences().get(0).id();
        mvc.perform(multipart(endpoint).file(file).with(user(admin)).with(csrf())).andExpect(status().isOk());
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?", String.class, copiedSentence)).isEqualTo(url);
        adminContent.deleteSentence(copy.id(), copiedSentence);
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        // Keep old objects for the restore window; force only this fixture's intent due.
        db.update("UPDATE s3_media_lifecycle SET cleanup_after=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE audio_url=?", url);
        lifecycle.cleanup();
        then(s3).should().deleteObject(DeleteObjectRequest.builder().bucket("audio-test")
                .key("dictalab/audio/" + url.substring("/media/s3/".length())).build());
    }
}
