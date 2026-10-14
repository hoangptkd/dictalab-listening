package com.dictalab.service;

import com.dictalab.config.MediaStorageProperties;
import com.dictalab.exception.MediaStorageException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaStorageService {

    private static final String S3_URL_PREFIX = "/media/s3/";
    private static final java.util.regex.Pattern S3_FILENAME = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp3|m4a|wav)");

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/mp4",
            "audio/x-m4a", "application/octet-stream"
    );

    private final Path root;
    private final long maxBytes;
    private final MediaStorageProperties.S3 config;
    private final Optional<S3Client> s3;
    private final Optional<S3Presigner> presigner;
    private final org.springframework.beans.factory.ObjectProvider<S3MediaLifecycle> lifecycle;

    public MediaStorageService(MediaStorageProperties properties, Optional<S3Client> s3,
                               Optional<S3Presigner> presigner) {
        this(properties, s3, presigner, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MediaStorageService(MediaStorageProperties properties, Optional<S3Client> s3,
                               Optional<S3Presigner> presigner,
                               org.springframework.beans.factory.ObjectProvider<S3MediaLifecycle> lifecycle) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.maxBytes = properties.maxFileSize().toBytes();
        this.config = properties.s3();
        this.s3 = s3;
        this.presigner = presigner;
        this.lifecycle = lifecycle;
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo thư mục lưu audio: " + root, exception);
        }
    }

    public StoredMedia storeAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MediaStorageException("Vui lòng chọn một file audio.");
        }
        if (file.getSize() > maxBytes) {
            throw new MediaStorageException("File audio vượt quá giới hạn " + (maxBytes / 1024 / 1024) + " MB.");
        }
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new MediaStorageException("Định dạng audio chưa được hỗ trợ. Hãy dùng MP3, M4A hoặc WAV.");
        }

        String originalExtension = extensionOf(file.getOriginalFilename());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(temporary) > maxBytes) {
                throw new MediaStorageException("File audio vượt quá giới hạn cho phép.");
            }
            String detectedExtension = detectAudioExtension(temporary);
            if (!detectedExtension.equals(originalExtension)) {
                throw new MediaStorageException("Phần mở rộng file không khớp với nội dung audio thực tế.");
            }
            String storedName = UUID.randomUUID() + "." + detectedExtension;
            if (config.enabled()) {
                if (lifecycle != null) lifecycle.getObject().stage(S3_URL_PREFIX + storedName);
                s3.orElseThrow(() -> new IllegalStateException("Missing media S3 client"))
                        .putObject(PutObjectRequest.builder().bucket(config.bucket())
                                .key(config.prefix() + storedName).contentType(audioContentType(detectedExtension))
                                .build(), RequestBody.fromFile(temporary));
                // Persist the stable application URL, never an expiring S3 signature.
                return new StoredMedia(S3_URL_PREFIX + storedName);
            }
            Path destination = safeResolve(storedName);
            moveAtomically(temporary, destination);
            temporary = null;
            return new StoredMedia("/media/" + storedName);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (SdkException exception) {
            // Do not fall back to disk: an S3 outage must not silently change storage location.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa lưu được audio lên S3. Vui lòng thử lại.", exception);
        } catch (IOException exception) {
            throw new MediaStorageException("Không thể lưu file audio. Vui lòng thử lại.", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // File tạm sẽ được dọn bởi quản trị hệ thống nếu việc xóa tức thời thất bại.
                }
            }
        }
    }

    public void deleteIfManaged(String audioUrl) {
        if (audioUrl != null && audioUrl.startsWith(S3_URL_PREFIX)) {
            String storedName = audioUrl.substring(S3_URL_PREFIX.length());
            validateS3Filename(storedName);
            requireS3();
            try {
                s3.orElseThrow().deleteObject(DeleteObjectRequest.builder().bucket(config.bucket())
                        .key(config.prefix() + storedName).build());
            } catch (SdkException exception) {
                throw new MediaStorageException("Không thể dọn file audio cũ trên S3.", exception);
            }
            return;
        }
        if (audioUrl == null || !audioUrl.startsWith("/media/")) {
            return;
        }
        String storedName = audioUrl.substring("/media/".length());
        if (storedName.contains("/") || storedName.contains("\\")) {
            return;
        }
        try {
            Files.deleteIfExists(safeResolve(storedName));
        } catch (IOException exception) {
            throw new MediaStorageException("Không thể dọn file audio cũ.", exception);
        }
    }

    public URI signedAudioUrl(String filename) {
        validateS3Filename(filename);
        requireS3();
        try {
            return URI.create(presigner.orElseThrow().presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(config.signedUrlDuration())
                    .getObjectRequest(GetObjectRequest.builder().bucket(config.bucket())
                            .key(config.prefix() + filename).build()).build()).url().toExternalForm());
        } catch (SdkException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa tải được audio từ S3. Vui lòng thử lại.", exception);
        }
    }

    private void requireS3() {
        if (!config.enabled() || s3.isEmpty() || presigner.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lưu trữ audio S3 chưa được cấu hình.");
        }
    }

    private void validateS3Filename(String filename) {
        if (filename == null || !S3_FILENAME.matcher(filename).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy audio.");
        }
    }

    private String audioContentType(String extension) {
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            default -> throw new IllegalArgumentException("Unsupported audio extension");
        };
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            throw new MediaStorageException("Tên file audio không hợp lệ.");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("mp3", "m4a", "wav").contains(extension)) {
            throw new MediaStorageException("Chỉ hỗ trợ file MP3, M4A hoặc WAV.");
        }
        return extension;
    }

    private String detectAudioExtension(Path file) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(file)) {
            header = input.readNBytes(12);
        }
        int length = header.length;
        if (length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return "mp3";
        }
        if (length >= 2 && Byte.toUnsignedInt(header[0]) == 0xFF
                && (Byte.toUnsignedInt(header[1]) & 0xE0) == 0xE0) {
            return "mp3";
        }
        if (length >= 12 && text(header, 0, 4).equals("RIFF") && text(header, 8, 4).equals("WAVE")) {
            return "wav";
        }
        if (length >= 12 && text(header, 4, 4).equals("ftyp")) {
            return "m4a";
        }
        throw new MediaStorageException("File tải lên không phải audio MP3, M4A hoặc WAV hợp lệ.");
    }

    private String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private Path safeResolve(String filename) {
        Path resolved = root.resolve(filename).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new MediaStorageException("Đường dẫn lưu audio không hợp lệ.");
        }
        return resolved;
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    public record StoredMedia(String publicUrl) {
    }
}
