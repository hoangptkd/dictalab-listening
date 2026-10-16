package com.dictalab.service;

import com.dictalab.exception.MediaStorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest(properties={"dictalab.media.s3.enabled=true", "dictalab.media.s3.bucket=lifecycle-test",
        "dictalab.media.s3.region=ap-southeast-1", "dictalab.media.s3.cleanup-delay-ms=600000",
        "dictalab.media.s3.cleanup-enabled=true"})
class S3MediaLifecycleIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired S3MediaLifecycle lifecycle;
    @Autowired MediaStorageService media;
    @Autowired AdminContentService admin;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.dictalab.config.MediaStorageProperties storageProperties;
    @Autowired org.springframework.beans.factory.ObjectProvider<MediaStorageService> mediaProvider;
    @MockitoBean S3Client s3;
    @MockitoBean S3Presigner signer;
    private final List<String> urls=new ArrayList<>();
    private final List<Long> lessons=new ArrayList<>();
    private final MockMultipartFile file=new MockMultipartFile("file","clip.mp3","audio/mpeg",new byte[]{'I','D','3',4});

    @AfterEach void cleanupFixtures() {
        lessons.forEach(id->db.update("DELETE FROM lessons WHERE id=?",id));
        urls.forEach(url->db.update("DELETE FROM s3_media_lifecycle WHERE audio_url=?",url));
    }
    private String staged() {
        String url="/media/s3/"+UUID.randomUUID()+".mp3"; urls.add(url); lifecycle.stage(url); return url;
    }
    private void due(String url) {db.update("UPDATE s3_media_lifecycle SET cleanup_after=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE audio_url=?",url);}
    private String status(String url) {return db.queryForObject("SELECT status FROM s3_media_lifecycle WHERE audio_url=?",String.class,url);}

    @Test void cleanupRequiresExplicitOptInAndDisabledWorkerDoesNotClaimDueIntent() {
        var yaml=new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"));
        assertThat(yaml.getObject().getProperty("dictalab.media.s3.cleanup-enabled"))
                .isEqualTo("${DICTALAB_MEDIA_S3_CLEANUP_ENABLED:false}");
        String url=staged(); due(url);
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var disabled=new S3MediaLifecycle(db,manager,storageProperties,mediaProvider,registry,7,false);
            disabled.cleanup();
        } finally {registry.close();}
        assertThat(status(url)).isEqualTo("STAGED");
        assertThat(db.queryForObject("SELECT attempts FROM s3_media_lifecycle WHERE audio_url=?",Integer.class,url)).isZero();
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void uploadIntentCommitsBeforeS3AndAttachmentCommitsAfterWithoutHoldingATransaction() {
        var lesson=admin.copyLesson(1L); lessons.add(lesson.id());
        given(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).willAnswer(call->{
            String url="/media/s3/"+call.getArgument(0,PutObjectRequest.class).key().substring("dictalab/audio/".length()); urls.add(url);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(status(url)).isEqualTo("STAGED");
            assertThat(db.queryForObject("SELECT cleanup_after>CURRENT_TIMESTAMP + INTERVAL '6' DAY FROM s3_media_lifecycle WHERE audio_url=?",Boolean.class,url)).isTrue();
            return PutObjectResponse.builder().build();
        });
        String url=admin.uploadAudio(lesson.id(),lesson.sentences().get(0).id(),file).sentences().get(0).audioUrl();
        assertThat(status(url)).isEqualTo("LIVE");
        assertThat(db.queryForObject("SELECT cleanup_after FROM s3_media_lifecycle WHERE audio_url=?",java.sql.Timestamp.class,url)).isNull();
    }

    @Test void uncertainPutLeavesIntentAndDoesNotOverwriteExistingReference() {
        var lesson=admin.copyLesson(1L); lessons.add(lesson.id());
        String original=lesson.sentences().get(0).audioUrl();
        given(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).willAnswer(call->{
            String url="/media/s3/"+call.getArgument(0,PutObjectRequest.class).key().substring("dictalab/audio/".length()); urls.add(url);
            assertThat(status(url)).isEqualTo("STAGED");
            throw SdkClientException.create("Simulated PUT accepted, response lost");
        });
        assertThatThrownBy(()->admin.uploadAudio(lesson.id(),lesson.sentences().get(0).id(),file))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?",String.class,lesson.sentences().get(0).id())).isEqualTo(original);
        String url=urls.get(0); due(url); lifecycle.cleanup();
        assertThat(status(url)).isEqualTo("DELETED");
        then(s3).should(times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));
        then(s3).should(times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void crashAfterUploadBeforeAttachIsReconciledAndLateAttachmentIsFenced() {
        String url=staged(); // Represents a committed intent + remote object, with no attachment commit.
        due(url); lifecycle.cleanup(); lifecycle.cleanup();
        assertThat(status(url)).isEqualTo("DELETED");
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->lifecycle.retain(url)))
                .isInstanceOf(MediaStorageException.class);
        then(s3).should(times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void deleteResponseLossRetriesOnlyDeleteAndKeepsTombstone() {
        String url=staged(); due(url);
        willThrow(SdkClientException.create("DELETE accepted, response lost")).given(s3).deleteObject(any(DeleteObjectRequest.class));
        lifecycle.cleanup();
        assertThat(status(url)).isEqualTo("DELETING");
        assertThat(db.queryForObject("SELECT cleanup_after>CURRENT_TIMESTAMP FROM s3_media_lifecycle WHERE audio_url=?",Boolean.class,url)).isTrue();
        lifecycle.cleanup(); then(s3).should(times(1)).deleteObject(any(DeleteObjectRequest.class));
        given(s3.deleteObject(any(DeleteObjectRequest.class))).willReturn(software.amazon.awssdk.services.s3.model.DeleteObjectResponse.builder().build());
        due(url); lifecycle.cleanup();
        assertThat(status(url)).isEqualTo("DELETED");
        then(s3).should(times(2)).deleteObject(any(DeleteObjectRequest.class));
        then(s3).should(never()).putObject(any(PutObjectRequest.class),any(RequestBody.class));
    }

    @Test void liveSharedReferenceCancelsCleanupAndReleaseRollsBackWithReferenceDeletion() {
        var lesson=admin.copyLesson(1L); lessons.add(lesson.id()); long sentence=lesson.sentences().get(0).id();
        String url=staged();
        new TransactionTemplate(manager).executeWithoutResult(tx->{lifecycle.retain(url); db.update("UPDATE lesson_sentences SET audio_url=? WHERE id=?",url,sentence); lifecycle.release(url);});
        due(url); lifecycle.cleanup(); assertThat(status(url)).isEqualTo("LIVE");
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        new TransactionTemplate(manager).executeWithoutResult(tx->{admin.deleteSentence(lesson.id(),sentence); tx.setRollbackOnly();});
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?",String.class,sentence)).isEqualTo(url);
        assertThat(db.queryForObject("SELECT cleanup_after FROM s3_media_lifecycle WHERE audio_url=?",java.sql.Timestamp.class,url)).isNull();
    }

    @Test void expiredUploadAndChangedStorageIdentityCannotAttachOrDeleteWrongBucket() {
        String expired=staged();
        db.update("UPDATE s3_media_lifecycle SET upload_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE audio_url=?",expired);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->lifecycle.retain(expired)))
                .isInstanceOf(MediaStorageException.class);
        String other=staged(); db.update("UPDATE s3_media_lifecycle SET storage_identity='different-bucket/prefix' WHERE audio_url=?",other); due(other);
        lifecycle.cleanup(); assertThat(status(other)).isEqualTo("STAGED");
        then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void competingCleanupWorkerCannotDeleteDuringActiveLease() throws Exception {
        String url=staged(); due(url); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        given(s3.deleteObject(any(DeleteObjectRequest.class))).willAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            return software.amazon.awssdk.services.s3.model.DeleteObjectResponse.builder().build();
        });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var worker=pool.submit(lifecycle::cleanup); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            lifecycle.cleanup();
            assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->lifecycle.retain(url))).isInstanceOf(MediaStorageException.class);
            release.countDown(); worker.get(5,TimeUnit.SECONDS); assertThat(status(url)).isEqualTo("DELETED");
        } finally {release.countDown();pool.shutdownNow();}
        then(s3).should(times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void recoveryAfterCleanupWorkerCrashReclaimsExpiredDeleteLease() {
        String url=staged();
        db.update("UPDATE s3_media_lifecycle SET status='DELETING',lease_id=?,cleanup_after=CURRENT_TIMESTAMP + INTERVAL '120' SECOND WHERE audio_url=?",UUID.randomUUID().toString(),url);
        lifecycle.cleanup(); then(s3).should(never()).deleteObject(any(DeleteObjectRequest.class));
        due(url); lifecycle.cleanup(); assertThat(status(url)).isEqualTo("DELETED");
        then(s3).should(times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void editRevisionChangedDuringUploadRejectsAttachmentWithoutChangingAudio() {
        var lesson=admin.copyLesson(1L);lessons.add(lesson.id());
        String revision=db.queryForObject("SELECT updated_at FROM lessons WHERE id=?",java.sql.Timestamp.class,lesson.id()).toInstant().toString();
        String previous=lesson.sentences().get(0).audioUrl();
        given(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).willAnswer(call->{
            urls.add("/media/s3/"+call.getArgument(0,PutObjectRequest.class).key().substring("dictalab/audio/".length()));
            db.update("UPDATE lessons SET updated_at=updated_at + INTERVAL '1' SECOND WHERE id=?",lesson.id());
            return PutObjectResponse.builder().build();
        });
        assertThatThrownBy(()->admin.uploadAudio(lesson.id(),lesson.sentences().get(0).id(),file,revision,null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(409));
        assertThat(db.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?",String.class,lesson.sentences().get(0).id())).isEqualTo(previous);
        assertThat(status(urls.get(0))).isEqualTo("STAGED");
    }
}
