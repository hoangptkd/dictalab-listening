import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.nio.file.*;
import software.amazon.awssdk.retries.StandardRetryStrategy;

/** Bounded production probe. Credentials use the SDK chain and are never logged. */
public class S3AudioStorageCheck {
    public static void main(String[] args) {
        String bucket = "dictalab-audio-20261004-6ac4f43c";
        String key = "dictalab/audio/s3-release-check-" + UUID.randomUUID() + ".wav";
        String restoredKey = "dictalab/audio/s3-restore-check-" + UUID.randomUUID() + ".wav";
        byte[] bytes = "RIFF-S3-release-check-WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try (var s3 = S3Client.builder().region(Region.US_EAST_1)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
             var signer = S3Presigner.builder().region(Region.US_EAST_1).build()) {
            boolean uploaded = false;
            boolean restoreAttempted = false;
            try {
                uploaded = true; // Reconcile this known synthetic key even after an uncertain PUT.
                s3.putObject(r -> r.bucket(bucket).key(key).contentType("audio/wav"), RequestBody.fromBytes(bytes));
                if (!Arrays.equals(bytes, s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray()))
                    throw new IllegalStateException("Object byte mismatch");
                System.out.println("S3_PUT_GET_BYTES=OK");
                URI signed = signer.presignGetObject(r -> r.signatureDuration(Duration.ofMinutes(2))
                        .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toURI();
                String origin = System.getenv().getOrDefault("DICTALAB_PUBLIC_URL", "https://160.191.244.27:18443");
                var response = http.send(HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(20))
                        .header("Origin", origin).header("Range", "bytes=0-3").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 206 || response.body().length != 4 ||
                        !origin.equals(response.headers().firstValue("Access-Control-Allow-Origin").orElse("")))
                    throw new IllegalStateException("Range or CORS mismatch");
                System.out.println("S3_SIGNED_RANGE_CORS=OK");
                URI unsigned = URI.create("https://" + bucket + ".s3.us-east-1.amazonaws.com/" + key);
                if (http.send(HttpRequest.newBuilder(unsigned).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() != 403)
                    throw new IllegalStateException("Unsigned object is not private");
                System.out.println("S3_UNSIGNED_ACCESS_DENIED=OK");
                try {
                    s3.getObjectAsBytes(r -> r.bucket(bucket).key("outside-audio/" + UUID.randomUUID()));
                    throw new IllegalStateException("Outside-prefix read unexpectedly allowed");
                } catch (S3Exception denied) { if (denied.statusCode() != 403) throw denied; }
                try {
                    s3.listObjectsV2(r -> r.bucket(bucket).maxKeys(1));
                    throw new IllegalStateException("Bucket listing unexpectedly allowed");
                } catch (S3Exception denied) { if (denied.statusCode() != 403) throw denied; }
                System.out.println("S3_LEAST_PRIVILEGE_DENIALS=OK");
                String recoveryRoot = System.getenv("S3_RECOVERY_BACKUP_DIR");
                if (recoveryRoot != null) {
                    Path backup = Path.of(recoveryRoot).resolve("synthetic-audio.wav");
                    Files.createDirectories(backup.getParent());
                    Files.write(backup, s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray(), StandardOpenOption.CREATE_NEW);
                    s3.deleteObject(r -> r.bucket(bucket).key(key));
                    restoreAttempted = true;
                    s3.putObject(r -> r.bucket(bucket).key(restoredKey).contentType("audio/wav"), RequestBody.fromFile(backup));
                    if (!Arrays.equals(Files.readAllBytes(backup), s3.getObjectAsBytes(r -> r.bucket(bucket).key(restoredKey)).asByteArray()))
                        throw new IllegalStateException("Restored synthetic object byte mismatch");
                    System.out.println("S3_SYNTHETIC_BACKUP_RESTORE_BYTES=OK");
                }
            } finally {
                if (restoreAttempted) {
                    s3.deleteObject(r -> r.bucket(bucket).key(restoredKey));
                    System.out.println("S3_SYNTHETIC_RESTORED_OBJECT_DELETED=OK");
                }
                if (uploaded) {
                    s3.deleteObject(r -> r.bucket(bucket).key(key));
                    System.out.println("S3_SYNTHETIC_OBJECT_DELETED=OK");
                }
            }
        } catch (Exception error) {
            // Do not expose signed URLs, HTTP request details or provider exceptions.
            System.out.println("S3_STORAGE_CHECK_FAILED=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
