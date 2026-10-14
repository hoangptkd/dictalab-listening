package com.dictalab.service;

import com.dictalab.config.MediaStorageProperties;
import com.dictalab.exception.MediaStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

class MediaStorageServiceTest {
    @TempDir Path root;
    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner signer = mock(S3Presigner.class);
    private static final byte[] MP3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
    private static final String NAME = "2ac5e402-a6df-480f-a741-5f3f851876cf.mp3";

    private MediaStorageService service(boolean enabled) {
        var properties = new MediaStorageProperties(root, DataSize.ofKilobytes(1),
                new MediaStorageProperties.S3(enabled, "audio-test", "ap-southeast-1", "dictalab/audio", null));
        var service = new MediaStorageService(properties, Optional.of(s3), Optional.of(signer));
        service.initialize();
        return service;
    }

    private MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test void localModeStillStoresAndDeletesWithoutAws() throws Exception {
        var service = service(false);
        String url = service.storeAudio(upload("clip.mp3", MP3)).publicUrl();
        assertThat(url).startsWith("/media/").doesNotContain("/s3/");
        Path file = root.resolve(url.substring("/media/".length()));
        assertThat(Files.readAllBytes(file)).isEqualTo(MP3);
        service.deleteIfManaged(url);
        assertThat(file).doesNotExist();
        then(s3).shouldHaveNoInteractions();
    }

    @Test void s3UploadHasStableIdentityValidatedTypeAndNoPublicAclOrLocalCopy() throws Exception {
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willAnswer(invocation -> {
            var request = invocation.getArgument(0, PutObjectRequest.class);
            assertThat(request.bucket()).isEqualTo("audio-test");
            assertThat(request.key()).startsWith("dictalab/audio/").endsWith(".mp3");
            assertThat(request.contentType()).isEqualTo("audio/mpeg");
            assertThat(request.acl()).isNull();
            try (var input = invocation.getArgument(1, RequestBody.class).contentStreamProvider().newStream()) {
                assertThat(input.readAllBytes()).isEqualTo(MP3);
            }
            return PutObjectResponse.builder().eTag("test-etag").build();
        });
        var service = service(true);
        String url = service.storeAudio(upload("clip.mp3", MP3)).publicUrl();
        assertThat(url).matches("/media/s3/[0-9a-f-]{36}\\.mp3").doesNotContain("X-Amz", "amazonaws");
        try (var files = Files.list(root)) { assertThat(files).isEmpty(); }
    }

    @Test void s3FailureDoesNotLeaveLocalFallbackOrTemporaryFile() throws Exception {
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willThrow(SdkClientException.create("network unavailable"));
        assertThatThrownBy(() -> service(true).storeAudio(upload("clip.mp3", MP3)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        try (var files = Files.list(root)) { assertThat(files).isEmpty(); }
    }

    @Test void rejectsSpoofedUnsupportedAndOversizedUploadsBeforeS3() throws Exception {
        var service = service(true);
        assertThatThrownBy(() -> service.storeAudio(upload("clip.wav", MP3)))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> service.storeAudio(upload("clip.mp3", new byte[12])))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> service.storeAudio(upload("clip.mp3", new byte[1025])))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> service.storeAudio(new MockMultipartFile("file", "clip.mp3", "text/html", MP3)))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> service.storeAudio(upload("clip.mp3", new byte[0])))
                .isInstanceOf(MediaStorageException.class);
        then(s3).shouldHaveNoInteractions();
        try (var files = Files.list(root)) { assertThat(files).isEmpty(); }
    }

    @Test void alsoChecksActualBytesWhenReportedSizeIsIncorrect() {
        var service = service(true);
        var upload = new MockMultipartFile("file", "clip.mp3", "audio/mpeg", new byte[1025]) {
            @Override public long getSize() { return 10; }
        };
        assertThatThrownBy(() -> service.storeAudio(upload)).isInstanceOf(MediaStorageException.class);
        then(s3).shouldHaveNoInteractions();
    }

    @Test void deletionUsesOnlyConfiguredBucketAndPrefixAndKeepsLegacyLocalSupport() throws Exception {
        var service = service(true);
        service.deleteIfManaged("/media/s3/" + NAME);
        then(s3).should().deleteObject(DeleteObjectRequest.builder().bucket("audio-test")
                .key("dictalab/audio/" + NAME).build());
        Path legacy = root.resolve("legacy.mp3");
        Files.write(legacy, MP3);
        service.deleteIfManaged("/media/legacy.mp3");
        assertThat(legacy).doesNotExist();
        service.deleteIfManaged("https://other.example/clip.mp3");
        service.deleteIfManaged("/audio/elllo/clip.mp3");
        then(s3).shouldHaveNoMoreInteractions();
    }

    @Test void cannotSignOrDeleteArbitraryKeysOrTraverseDirectories() {
        var service = service(true);
        for (String name : new String[]{"../secret.mp3", "evil.mp3", NAME + "?other=1", "answer.json", "..\\secret.mp3"}) {
            assertThatThrownBy(() -> service.signedAudioUrl(name)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.deleteIfManaged("/media/s3/" + name))
                    .isInstanceOf(ResponseStatusException.class);
        }
        then(s3).shouldHaveNoInteractions();
        then(signer).shouldHaveNoInteractions();
    }

    @Test void privateDownloadUsesRealSdkSignatureWithoutPersistingIt() {
        var properties = new MediaStorageProperties(root, null,
                new MediaStorageProperties.S3(true, "audio-test", "ap-southeast-1", "dictalab/audio", null));
        try (var realSigner = S3Presigner.builder().region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        "test-access-key", "test-secret-key"))).build()) {
            var service = new MediaStorageService(properties, Optional.of(s3), Optional.of(realSigner));
            var url = service.signedAudioUrl(NAME);
            assertThat(url.getScheme()).isEqualTo("https");
            assertThat(url.getHost()).isEqualTo("audio-test.s3.ap-southeast-1.amazonaws.com");
            assertThat(url.getPath()).isEqualTo("/dictalab/audio/" + NAME);
            assertThat(url.getQuery()).contains("X-Amz-Expires=1800", "X-Amz-Signature=")
                    .doesNotContain("test-secret-key");
        }
    }

    @Test void disabledS3DoesNotIssueLinksOrDeleteStoredS3Objects() {
        var service = service(false);
        assertThatThrownBy(() -> service.signedAudioUrl(NAME)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.deleteIfManaged("/media/s3/" + NAME))
                .isInstanceOf(ResponseStatusException.class);
        then(s3).shouldHaveNoInteractions();
        then(signer).shouldHaveNoInteractions();
    }

    @Test void incompleteS3ConfigurationFailsFast() {
        assertThatThrownBy(() -> new MediaStorageProperties.S3(true, "", "ap-southeast-1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaStorageProperties.S3(true, "audio-test", "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaStorageProperties.S3(true, "audio-test", "ap-southeast-1", "../audio", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaStorageProperties.S3(true, "audio-test", "ap-southeast-1", null, Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MediaStorageProperties(root, null, null).s3().enabled()).isFalse();
    }
}
