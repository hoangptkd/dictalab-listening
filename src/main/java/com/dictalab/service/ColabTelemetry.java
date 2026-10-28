package com.dictalab.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Write-only per-run credentials. Remote assertions never become locally verified checkpoints. */
@Service
public class ColabTelemetry {
    // shortcut: one JVM writes this private file store; use transactional shared storage before adding replicas.
    private final ObjectMapper json;
    private final boolean enabled;
    private final Path directory;
    private final String publicUrl;
    private static final Set<String> STATUSES = Set.of("WAITING", "VERIFYING", "RUNNING", "FAILED", "INTERRUPTED", "SMOKE_PASSED", "REPORTED_COMPLETE", "PAUSED_BY_USER", "STOPPED_BY_USER");
    private static final Set<String> HASHES = Set.of("configHash", "datasetHash", "datasetManifestHash", "reviewHash", "environmentLockHash", "inspectionHash", "trainerHash");
    private static final Set<String> METRICS = Set.of("step", "loss", "grad_norm", "learning_rate", "epoch", "eval_loss", "entropy", "mean_token_accuracy", "num_tokens");

    public ColabTelemetry(ObjectMapper json,
                          @Value("${dictalab.training.colab.enabled:false}") boolean enabled,
                          @Value("${dictalab.training.colab.directory:data/fine-tuning/studio/colab}") String directory,
                          @Value("${dictalab.training.colab.public-url:https://dictalab.hoangptkdhy.workers.dev}") String publicUrl) {
        this.json = json;
        this.enabled = enabled;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        var uri = java.net.URI.create(publicUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !Set.of("", "/").contains(uri.getPath()))
            throw new IllegalArgumentException("Colab public URL must be an HTTPS origin");
        this.publicUrl = publicUrl.replaceAll("/$", "");
    }

    public boolean enabled() { return enabled; }

    private void ready() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Đồng bộ Colab chưa bật trên máy chủ.");
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private Path path(String id) {
        if (id == null || !id.matches("(?:colab|local)-[a-f0-9]{32}")) throw invalid("Mã phiên từ xa không hợp lệ.");
        return directory.resolve(id + ".json");
    }

    private ObjectNode read(String id) {
        try {
            Path path = path(id);
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > 200_000)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên Colab.");
            return (ObjectNode) json.readTree(Files.readString(path));
        } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không đọc được metadata Colab."); }
    }

    private void save(String id, ObjectNode value) { saveFile(path(id), value); }

    private void saveFile(Path destination, ObjectNode value) {
        Path temporary = null;
        try {
            Path parent = destination.getParent();
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(parent)) throw new java.io.IOException("Symlink directory");
            try { Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { /* Windows inherits the configured private directory ACL. */ }
            temporary = Files.createTempFile(parent, ".colab-", ".tmp");
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.writeString(temporary, json.writeValueAsString(value), StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không lưu được metadata Colab; chưa xác nhận nhận dữ liệu."); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { } }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private List<String> ids() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.matches("(?:colab|local)-[a-f0-9]{32}\\.json"))
                    .sorted().limit(201).map(n -> n.substring(0, n.length() - 5)).toList();
        } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không liệt kê được các phiên Colab."); }
    }

    public synchronized Map<String, Object> register(Map<String, Object> body, Long actor) {
        ready();
        if (!Set.of("name", "notebookUrl", "source").containsAll(body.keySet())) throw invalid("Chỉ nhận tên, nguồn và URL notebook.");
        String source = Objects.toString(body.getOrDefault("source", "colab"));
        if (!Set.of("colab", "local").contains(source)) throw invalid("Nguồn train không hợp lệ.");
        String name = Objects.toString(body.get("name"), "").strip();
        String notebook = Objects.toString(body.get("notebookUrl"), "").strip();
        if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) throw invalid("Tên phiên cần 1–100 ký tự.");
        if (source.equals("colab") && !notebook.matches("https://colab\\.research\\.google\\.com/drive/[A-Za-z0-9_-]{10,100}")) throw invalid("Cần URL notebook Colab trên Google Drive, không kèm query hoặc fragment.");
        if (source.equals("local") && !notebook.isEmpty()) throw invalid("Phiên local không nhận URL notebook.");
        if (ids().size() >= 200) throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã đạt giới hạn 200 phiên Colab.");
        String id = source + "-" + UUID.randomUUID().toString().replace("-", "");
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var row = json.createObjectNode();
        row.put("id", id).put("source", source).put("name", name).put("notebookUrl", notebook).put("createdBy", String.valueOf(actor))
                .put("createdAt", Instant.now().toString()).put("expiresAt", Instant.now().plus(Duration.ofDays(7)).toString())
                .put("tokenHash", digest(token)).put("sequence", 0).put("revoked", false);
        save(id, row);
        return Map.of("id", id, "token", token, "expiresAt", row.path("expiresAt").asText(),
                "endpoint", publicUrl + "/api/training/telemetry/" + id,
                "message", "Khóa chỉ gửi tiến độ cho phiên này, hết hạn sau 7 ngày; chỉ hiển thị một lần.");
    }

    public synchronized Map<String, Object> revoke(String id) {
        ready(); var row = read(id); row.put("revoked", true); save(id, row);
        return Map.of("message", "Đã thu hồi khóa đồng bộ; giữ nguyên bằng chứng và không dừng trainer.");
    }

    // Reject unknown fields instead of accepting logs, prompts, dataset contents or arbitrary remote paths.
    private void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid("Metadata cần là JSON object.");
        node.properties().forEach(e -> { if (!allowed.contains(e.getKey())) throw invalid("Metadata có trường không được phép."); });
    }

    private void numeric(JsonNode node, Set<String> allowed) {
        fields(node, allowed);
        node.properties().forEach(e -> { if (!e.getValue().isNumber() || !Double.isFinite(e.getValue().asDouble())) throw invalid("Metrics cần là số hữu hạn."); });
    }

    private void hash(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[a-f0-9]{64}")) throw invalid("Hash SHA-256 không hợp lệ.");
    }

    private void validate(JsonNode report) {
        fields(report, Set.of("schemaVersion", "sequence", "observedAt", "phase", "status", "step", "plannedSteps", "startedAt", "finishedAt", "model", "revision", "device", "dtype", "gpu", "bindings", "config", "history", "validation", "optimizerEvidence", "resources", "checkpoints", "artifacts", "failure", "dataset", "attempt"));
        if (!report.path("schemaVersion").isIntegralNumber() || report.path("schemaVersion").asInt() != 1 || !report.path("sequence").isIntegralNumber() || report.path("sequence").asLong() < 1
                || !Set.of("smoke", "full").contains(report.path("phase").asText()) || !STATUSES.contains(report.path("status").asText())) throw invalid("Schema hoặc trạng thái Colab không hợp lệ.");
        try {
            if (Duration.between(Instant.parse(report.path("observedAt").asText()), Instant.now()).abs().compareTo(Duration.ofMinutes(5)) > 0) throw invalid("Snapshot quá cũ hoặc đồng hồ lệch quá 5 phút.");
            for (String k : List.of("startedAt", "finishedAt")) if (report.hasNonNull(k)) Instant.parse(report.path(k).asText());
        } catch (java.time.format.DateTimeParseException e) { throw invalid("Timestamp Colab không hợp lệ."); }
        for (String k : List.of("step", "plannedSteps")) if (!report.path(k).isIntegralNumber() || report.path(k).asLong() < 0 || report.path(k).asLong() > 1_000_000) throw invalid("Số step không hợp lệ.");
        int step = report.path("step").asInt(), planned = report.path("plannedSteps").asInt();
        if (planned < 1 || step > planned || (report.path("phase").asText().equals("smoke") && planned != 1)) throw invalid("Tiến độ smoke và full phải tách riêng.");
        if (report.has("attempt")) {
            var attempt = report.path("attempt");
            fields(attempt, Set.of("id", "fromStep"));
            if (!attempt.path("id").asText().matches("attempt-[a-f0-9]{32}") || !attempt.path("fromStep").isIntegralNumber()
                    || attempt.path("fromStep").asLong() < 0 || attempt.path("fromStep").asLong() > step)
                throw invalid("Mã lần tiếp tục hoặc checkpoint bắt đầu không hợp lệ.");
        }
        if (!report.path("model").asText().equals("Qwen/Qwen3.5-4B") || !report.path("revision").asText().matches("[a-f0-9]{40}")
                || !Set.of("cuda", "cpu").contains(report.path("device").asText()) || !Set.of("float16", "bfloat16", "float32").contains(report.path("dtype").asText()) ) throw invalid("Model hoặc thiết bị không hợp lệ.");
        if (report.path("gpu").asText().length() > 100) throw invalid("Tên GPU quá dài.");
        fields(report.path("bindings"), HASHES);
        for (String k : HASHES) hash(report.path("bindings").path(k));
        var config = report.path("config");
        fields(config, Set.of("base_model", "base_revision", "device", "seed", "max_seq_length", "packing", "loss_mask_policy", "assistant_only_loss", "enable_thinking", "lora", "quantization", "training"));
        numeric(config.path("lora"), Set.of("r", "alpha", "dropout"));
        fields(config.path("quantization"), Set.of("compute_dtype", "load_in_4bit", "quant_type", "double_quant"));
        for (String k : List.of("load_in_4bit", "double_quant")) if (config.path("quantization").has(k) && !config.path("quantization").path(k).isBoolean()) throw invalid("Quantization boolean không hợp lệ.");
        if (config.path("quantization").has("quant_type") && !Set.of("nf4", "fp4").contains(config.path("quantization").path("quant_type").asText())) throw invalid("Quantization type không hợp lệ.");
        var training = (ObjectNode)config.path("training").deepCopy();
        if (training.has("scheduler") && (!training.path("scheduler").isTextual() || !training.path("scheduler").asText().matches("[a-z_]{1,40}"))) throw invalid("Scheduler không hợp lệ.");
        for (String k : List.of("gradient_checkpointing", "use_cache")) if (training.has(k) && !training.path(k).isBoolean()) throw invalid("Training boolean không hợp lệ.");
        training.remove("scheduler"); training.remove("gradient_checkpointing"); training.remove("use_cache");
        numeric(training, Set.of("epochs", "per_device_batch_size", "gradient_accumulation_steps", "learning_rate", "warmup_ratio", "checkpoint_steps"));
        if (!config.path("base_model").equals(report.path("model")) || !config.path("base_revision").equals(report.path("revision"))
                || !config.path("device").equals(report.path("device")) || !config.path("quantization").path("compute_dtype").equals(report.path("dtype"))
                || !config.path("loss_mask_policy").asText().equals("last-assistant-only") || config.path("packing").asBoolean(true)
                || !config.path("assistant_only_loss").asBoolean() || config.path("max_seq_length").asInt() < 4608)
            throw invalid("Cấu hình model/mask không khớp metadata.");
        for (String k : List.of("seed", "max_seq_length")) if (!config.path(k).isIntegralNumber()) throw invalid("Cấu hình số nguyên không hợp lệ.");
        for (String k : List.of("packing", "assistant_only_loss", "enable_thinking")) if (!config.path(k).isBoolean()) throw invalid("Cấu hình boolean không hợp lệ.");
        if (!report.path("history").isArray() || report.path("history").size() > 120) throw invalid("History vượt giới hạn.");
        for (var item : report.path("history")) { numeric(item, METRICS); if (item.has("step") && (item.path("step").asDouble() < 0 || item.path("step").asDouble() > step)) throw invalid("History vượt optimizer step hiện tại."); }
        numeric(report.path("validation"), Set.of("eval_loss", "eval_runtime", "eval_samples_per_second", "eval_steps_per_second", "epoch"));
        var updates = report.path("optimizerEvidence");
        fields(updates, Set.of("actualOptimizerUpdates", "restoredOptimizerSteps", "changedAdapterTensors", "finiteLoggedGradients", "initialAmpScale"));
        for (String k : List.of("actualOptimizerUpdates", "restoredOptimizerSteps", "changedAdapterTensors")) if (updates.has(k) && (!updates.path(k).isIntegralNumber() || updates.path(k).asLong() < 0 || updates.path(k).asLong() > 1_000_000)) throw invalid("Bằng chứng optimizer không hợp lệ.");
        if (updates.has("finiteLoggedGradients") && !updates.path("finiteLoggedGradients").isBoolean()) throw invalid("Bằng chứng gradient không hợp lệ.");
        if (updates.has("initialAmpScale") && (!updates.path("initialAmpScale").isNumber() || !Double.isFinite(updates.path("initialAmpScale").asDouble()))) throw invalid("AMP scale không hợp lệ.");
        numeric(report.path("resources"), Set.of("gpuTotalBytes", "gpuUsedBytes", "gpuUtilization", "peakAllocatedBytes", "ramTotal", "ramAvailable", "ramPercent", "diskFree", "diskTotal", "cpuPercent", "cpuCores"));
        if (!report.path("checkpoints").isArray() || report.path("checkpoints").size() > 2) throw invalid("Tối đa 2 checkpoint trong snapshot.");
        for (var c : report.path("checkpoints")) {
            fields(c, Set.of("name", "step", "integrityReported", "fileCount"));
            if (!c.path("name").asText().matches("checkpoint-[1-9][0-9]{0,6}") || !c.path("step").isIntegralNumber() || c.path("step").asInt() < 1
                    || !c.path("name").asText().equals("checkpoint-" + c.path("step").asInt()) || c.path("step").asInt() > step
                    || !c.path("fileCount").isIntegralNumber() || c.path("fileCount").asInt() < 0 || c.path("fileCount").asInt() > 30 || !c.path("integrityReported").isBoolean()) throw invalid("Checkpoint metadata không hợp lệ.");
        }
        fields(report.path("artifacts"), Set.of("adapterHash", "adapterConfigHash"));
        report.path("artifacts").properties().forEach(e -> hash(e.getValue()));
        if (report.has("dataset")) {
            var d = report.path("dataset");
            fields(d, Set.of("records", "articles", "reviewers", "reportedApproved", "humanApproved", "reportedHoldout", "reportedNearDuplicate"));
            numeric(d.path("records"), Set.of("train", "validation", "test"));
            numeric(d.path("reviewers"), Set.of("AI:Codex", "AI:chatgpt-web"));
            for (String field : List.of("records", "reviewers")) d.path(field).properties().forEach(e -> { if (!e.getValue().isIntegralNumber() || e.getValue().asLong() < 0 || e.getValue().asLong() > 1_000_000) throw invalid("Dataset counts không hợp lệ."); });
            if (!d.path("articles").isIntegralNumber() || d.path("articles").asLong() < 0 || d.path("articles").asLong() > 1_000_000) throw invalid("Article count không hợp lệ.");
            for (String field : List.of("reportedApproved", "humanApproved", "reportedHoldout", "reportedNearDuplicate")) if (!d.path(field).isBoolean()) throw invalid("Approval flag không hợp lệ.");
        }
        if (report.has("failure") && !Set.of("runtime", "nonfinite_gradient", "missing_update", "out_of_memory", "incomplete_evidence", "none").contains(report.path("failure").asText())) throw invalid("Mã lỗi không hợp lệ.");
        String status = report.path("status").asText();
        if (Set.of("SMOKE_PASSED", "REPORTED_COMPLETE").contains(status)) {
            long restored = updates.path("restoredOptimizerSteps").asLong();
            if (restored > 0 && (!report.has("attempt") || restored != report.path("attempt").path("fromStep").asInt())) throw invalid("Bước khôi phục không khớp lần tiếp tục.");
            if (updates.path("actualOptimizerUpdates").asLong() < 1 || updates.path("actualOptimizerUpdates").asLong() + restored != step || step != planned || step < 1 || !updates.path("finiteLoggedGradients").asBoolean()
                    || report.path("device").asText().equals("cuda") && updates.path("changedAdapterTensors").asInt() < 1
                    || !report.path("artifacts").has("adapterHash") || !report.path("artifacts").has("adapterConfigHash")) throw invalid("Chưa có bằng chứng cập nhật optimizer/adapter hữu hạn.");
            if (status.equals("SMOKE_PASSED") != report.path("phase").asText().equals("smoke")) throw invalid("Không được gắn smoke thành full training.");
            if (status.equals("REPORTED_COMPLETE") && !report.path("validation").has("eval_loss")) throw invalid("Full training chưa có validation loss.");
        }
    }

    private String attemptId(JsonNode report) { return report.path("attempt").path("id").asText("legacy"); }

    private ObjectNode attemptIdentity(JsonNode report) {
        var identity = json.createObjectNode();
        for (String field : List.of("attempt", "bindings", "config", "phase", "model", "revision", "plannedSteps", "dataset", "device", "dtype"))
            if (report.has(field)) identity.set(field, report.path(field).deepCopy());
        return identity;
    }

    private Path archivePath(String id, String attempt) {
        path(id);
        if (!attempt.equals("legacy") && !attempt.matches("attempt-[a-f0-9]{32}")) throw invalid("Mã lần tiếp tục không hợp lệ.");
        return directory.resolve("attempts").resolve(id).resolve(attempt + ".json");
    }

    /** Approves metadata only. This never launches a trainer or verifies a remote checkpoint. */
    public synchronized Map<String, Object> approveAttempt(String id, Map<String, Object> body, Long actor) {
        ready();
        if (!body.keySet().equals(Set.of("fingerprint"))) throw invalid("Cần fingerprint chính xác của lần tiếp tục đang chờ.");
        var row = read(id);
        var pending = row.path("pendingAttempt");
        if (row.path("revoked").asBoolean() || !Instant.parse(row.path("expiresAt").asText()).isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Khóa đã hết hạn hoặc bị thu hồi; không thể duyệt kết nối lại.");
        if (!pending.isObject() || !pending.path("fingerprint").asText().equals(body.get("fingerprint"))
                || Duration.between(Instant.parse(pending.path("receivedAt").asText()), Instant.now()).compareTo(Duration.ofMinutes(5)) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu đã đổi hoặc quá cũ. Chờ reporter gửi snapshot mới rồi kiểm tra lại.");
        var approval = json.createObjectNode();
        approval.set("identity", attemptIdentity(pending.path("report")));
        approval.put("approvedBy", String.valueOf(actor)).put("approvedAt", Instant.now().toString());
        row.set("attemptApproval", approval); save(id, row);
        return Map.of("message", "Đã duyệt lần tiếp tục. Reporter sẽ dùng lại khóa hiện có; trainer không bị tác động.");
    }

    public synchronized Map<String, Object> receive(String id, String authorization, byte[] bytes, boolean secure) {
        ready();
        if (!secure) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Colab chỉ đồng bộ qua HTTPS.");
        var row = read(id);
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!supplied.matches("[A-Za-z0-9_-]{43}") || !MessageDigest.isEqual(digest(supplied).getBytes(StandardCharsets.US_ASCII), row.path("tokenHash").asText().getBytes(StandardCharsets.US_ASCII))
                || row.path("revoked").asBoolean() || !Instant.parse(row.path("expiresAt").asText()).isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Khóa Colab không hợp lệ, đã thu hồi hoặc hết hạn.");
        if (bytes.length > 32_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Snapshot tối đa 32 KB.");
        JsonNode report;
        try { report = json.readTree(bytes); validate(report); }
        catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw invalid("Snapshot Colab không hợp lệ."); }
        if (row.path("source").asText("colab").equals("local") && (!report.path("device").asText().equals("cpu") || !report.path("phase").asText().equals("full"))) throw invalid("Publisher local hiện chỉ hỗ trợ CPU full SFT.");
        if (row.path("source").asText("colab").equals("colab") && !report.path("device").asText().equals("cuda")) throw invalid("Phiên Colab cần metadata CUDA.");
        String contentHash = digest(new String(bytes, StandardCharsets.UTF_8));
        long sequence = report.path("sequence").asLong(), previous = row.path("sequence").asLong();
        if (sequence == previous && contentHash.equals(row.path("contentHash").asText())) return Map.of("accepted", true, "sequence", sequence);
        if (sequence <= previous) throw new ResponseStatusException(HttpStatus.CONFLICT, "Snapshot cũ hoặc sequence bị tái sử dụng.");
        if (row.has("report")) {
            JsonNode before = row.path("report");
            // Dataset and model identity belong to the logical run; code/config belong to each attempt.
            for (String field : List.of("phase", "model", "revision", "plannedSteps", "dataset"))
                if (!before.path(field).equals(report.path(field))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Model/dataset/phase/đích step thay đổi; cần lần train riêng.");
            for (String field : List.of("datasetHash", "datasetManifestHash", "reviewHash"))
                if (!before.path("bindings").path(field).equals(report.path("bindings").path(field)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Dữ liệu hoặc bằng chứng duyệt đã đổi; cần lần train riêng.");
            if (attemptId(before).equals(attemptId(report))) {
                if (!attemptIdentity(before).equals(attemptIdentity(report)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã/config của lần tiếp tục đã đổi; reporter cần mã attempt mới, dùng lại khóa hiện có.");
                if (report.path("step").asInt() < before.path("step").asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Optimizer step không được lùi trong cùng lần tiếp tục.");
            } else {
                for (var archived : row.path("attempts")) if (archived.path("id").asText().equals(attemptId(report)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Lần tiếp tục cũ đã đóng; không nhận lại snapshot.");
                int from = report.path("attempt").path("fromStep").asInt(-1);
                if (from < 0 || from > before.path("step").asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkpoint bắt đầu vượt tiến độ đã nhận. Kiểm tra lại provenance.");
                if (row.path("attempts").size() >= 63) throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã đạt giới hạn 64 lần tiếp tục.");
                var identity = attemptIdentity(report);
                if (!identity.equals(row.path("attemptApproval").path("identity"))) {
                    var pending = json.createObjectNode();
                    pending.set("report", report.deepCopy());
                    pending.put("fingerprint", digest(json.writeValueAsString(identity))).put("receivedAt", Instant.now().toString());
                    row.set("pendingAttempt", pending); save(id, row);
                    return Map.of("accepted", false, "pendingApproval", true, "sequence", sequence);
                }
                // Archive the accepted snapshot before replacing it; a failed write leaves the active run intact.
                var archive = json.createObjectNode();
                archive.set("report", before.deepCopy());
                archive.put("receivedAt", row.path("receivedAt").asText());
                if (row.has("currentAttemptApproval")) archive.set("approval", row.path("currentAttemptApproval").deepCopy());
                saveFile(archivePath(id, attemptId(before)), archive);
                var summary = json.createObjectNode();
                summary.put("id", attemptId(before)).put("step", before.path("step").asInt())
                        .put("status", before.path("status").asText())
                        .put("startedAt", before.path("startedAt").asText()).put("receivedAt", row.path("receivedAt").asText());
                summary.set("bindings", before.path("bindings").deepCopy());
                summary.set("fromStep", before.has("attempt") ? before.path("attempt").path("fromStep") : json.nullNode());
                if (!row.has("attempts")) row.putArray("attempts");
                ((tools.jackson.databind.node.ArrayNode)row.path("attempts")).add(summary);
                row.set("currentAttemptApproval", row.path("attemptApproval").deepCopy());
                row.remove("pendingAttempt"); row.remove("attemptApproval");
            }
        }
        row.set("report", report); row.put("sequence", sequence).put("contentHash", contentHash).put("receivedAt", Instant.now().toString());
        save(id, row);
        return Map.of("accepted", true, "sequence", sequence);
    }

    public synchronized ObjectNode detail(String id) {
        ready(); var row = read(id); var report = row.path("report");
        boolean fresh = row.has("receivedAt") && Duration.between(Instant.parse(row.path("receivedAt").asText()), Instant.now()).compareTo(Duration.ofSeconds(90)) <= 0;
        boolean connected = fresh && !row.path("revoked").asBoolean() && Instant.parse(row.path("expiresAt").asText()).isAfter(Instant.now());
        String status = report.path("status").asText("UNKNOWN");
        var out = json.createObjectNode();
        out.put("id", id).put("source", row.path("source").asText("colab")).put("transport", "https").put("name", row.path("name").asText()).put("runId", id)
                .put("notebookUrl", row.path("notebookUrl").asText()).put("expiresAt", row.path("expiresAt").asText())
                .put("revoked", row.path("revoked").asBoolean()).put("connected", connected)
                .put("connection", connected ? "LIVE" : row.has("receivedAt") ? "STALE" : "NOT_CONNECTED")
                .put("receivedAt", row.path("receivedAt").asText()).put("observedAt", report.path("observedAt").asText())
                .put("status", status.equals("RUNNING") && !connected ? "UNKNOWN" : status).put("reportedStatus", status)
                .put("phase", report.path("phase").asText("unknown")).put("model", report.path("model").asText("Qwen/Qwen3.5-4B"))
                .put("revision", report.path("revision").asText()).put("method", "lora")
                .put("device", report.path("device").asText(row.path("source").asText().equals("local") ? "cpu" : "cuda")).put("dtype", report.path("dtype").asText())
                .put("gpu", report.path("gpu").asText()).put("step", report.path("step").asInt())
                .put("plannedSteps", report.path("plannedSteps").asInt()).put("active", connected && status.equals("RUNNING"))
                .put("configBound", report.has("bindings")).put("completionVerified", false)
                .put("canStart", false).put("canResume", false).put("canPause", false);
        if (!report.has("step")) out.putNull("step");
        for (String name : List.of("configHash", "datasetHash")) out.put(name, report.path("bindings").path(name).asText());
        for (String name : List.of("config", "history", "validation", "optimizerEvidence", "resources", "artifacts", "bindings", "dataset"))
            out.set(name, report.has(name) ? report.path(name).deepCopy() : (name.equals("history") ? json.createArrayNode() : json.createObjectNode()));
        for (String name : List.of("startedAt", "finishedAt")) out.set(name, report.path(name).isMissingNode() ? json.nullNode() : report.path(name));
        var cps = out.putArray("checkpoints");
        for (var c : report.path("checkpoints")) { var cp = (ObjectNode)c.deepCopy(); cp.put("verified", false); cps.add(cp); }
        var metrics = json.createObjectNode();
        for (var h : report.path("history")) if (h.has("loss")) metrics = (ObjectNode)h.deepCopy();
        out.set("metrics", metrics);
        out.put("log", "Colab gửi metrics có cấu trúc, không gửi raw log/nội dung dataset. Mở notebook để đọc log gốc.");
        out.put("error", report.path("failure").asText("none").equals("none") ? "" : "Colab báo lỗi: " + report.path("failure").asText());
        out.put("endpoint", publicUrl + "/api/training/telemetry/" + id);
        out.set("attempt", report.has("attempt") ? report.path("attempt").deepCopy() : json.createObjectNode().put("id", "legacy").putNull("fromStep"));
        out.set("attempts", row.has("attempts") ? row.path("attempts").deepCopy() : json.createArrayNode());
        if (row.has("pendingAttempt")) {
            var pending = (ObjectNode)row.path("pendingAttempt").deepCopy();
            pending.put("approved", attemptIdentity(pending.path("report")).equals(row.path("attemptApproval").path("identity")));
            out.set("pendingAttempt", pending);
        }
        return out;
    }

    public synchronized JsonNode attempt(String id, String attempt) {
        ready();
        var row = read(id);
        if (attemptId(row.path("report")).equals(attempt)) return row.path("report").deepCopy();
        Path file = archivePath(id, attempt);
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 40_000)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch sử lần tiếp tục.");
            return json.readTree(Files.readString(file)).path("report");
        } catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không đọc được lịch sử lần tiếp tục."); }
    }

    public synchronized List<ObjectNode> runs() {
        if (!enabled) return List.of();
        return ids().stream().map(id -> {
            var row = detail(id);
            row.put("pendingAttemptApproval", row.has("pendingAttempt"));
            for (String field : List.of("history", "config", "bindings", "log", "pendingAttempt", "attempts")) row.remove(field);
            return row;
        }).toList();
    }
}
