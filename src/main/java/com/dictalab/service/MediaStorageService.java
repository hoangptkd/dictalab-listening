package com.dictalab.service;

import com.dictalab.config.MediaStorageProperties;
import com.dictalab.exception.MediaStorageException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/mp4",
            "audio/x-m4a", "application/octet-stream"
    );

    private final Path root;
    private final long maxBytes;

    public MediaStorageService(MediaStorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.maxBytes = properties.maxFileSize().toBytes();
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
            String detectedExtension = detectAudioExtension(temporary);
            if (!detectedExtension.equals(originalExtension)) {
                throw new MediaStorageException("Phần mở rộng file không khớp với nội dung audio thực tế.");
            }
            String storedName = UUID.randomUUID() + "." + detectedExtension;
            Path destination = safeResolve(storedName);
            moveAtomically(temporary, destination);
            temporary = null;
            return new StoredMedia("/media/" + storedName);
        } catch (MediaStorageException exception) {
            throw exception;
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
        byte[] header = new byte[12];
        int length;
        try (InputStream input = Files.newInputStream(file)) {
            length = input.read(header);
        }
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
