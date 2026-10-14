# DictaLab — kiểm tra production chỉ đọc, 09/10/2026

Người dùng chọn thử trên production, không dựng staging. Đã xác định đúng VPS `160.191.244.27` bằng SSH với host key checking, kiểm tra service/artifact/DB và lấy mẫu tài nguyên. **Chưa chạy fault injection, tải hoặc triển khai** vì điều kiện an toàn chưa đạt. Production vẫn Running/health UP sau kiểm tra.

## Hiện trạng đã kiểm chứng

| Hạng mục | Quan sát trực tiếp |
|---|---|
| Host | `WIN-D1G2GS3O7J7`, `160.191.244.27` |
| Service | `DictaLab` Running; loopback health `18082/actuator/health` UP |
| JAR đang chạy | SHA256 `DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F`, 114.591.903 bytes |
| Java service | Executable trong `jre-17.0.20.101-hotspot`; chưa đọc version output của binary trong lượt này |
| PostgreSQL | 17.11, DB `dictalab`, Flyway V25; snapshot 8 users / 41 practice sessions |
| Durable AI jobs V26 | `public.ai_background_jobs` chưa có |
| S3 lifecycle V27 | `public.s3_media_lifecycle` chưa có |
| RabbitMQ Listening | Biến service `DICTALAB_LISTENING_QUEUE_ENABLED=false`; không kích hoạt lại |
| S3 | Service flag bật; bucket `dictalab-audio-20261004-6ac4f43c`, `us-east-1`, prefix `dictalab/audio/` |
| S3 references | Hai bảng `lesson_sentences` và `listening_units` đều có 0 URL `/media/s3/...` tại snapshot |
| Constraints | 0 public constraints chưa validate; không chứng minh toàn bộ invariants nghiệp vụ |
| DB backup gần nhất theo metadata | `dictalab-20261009-023345-268.dump`, 7.191.121 bytes, mtime 02:34:01 UTC+7 |

JAR khớp artifact production lịch sử trong [S3 notes](s3-audio.md), không khớp JAR local batch reliability mới. V26/V27 chưa có nghĩa là không được gán các test local durable jobs/S3 lifecycle cho production đang chạy. Zero S3 references **không chứng minh bucket rỗng**, không kiểm kê object mồ côi, và không thay thế recovery test.

Metadata backup chỉ cho biết file tồn tại/kích thước/thời gian; chưa kiểm tra hash, manifest, restore hoặc RPO/RTO của file này. Không dừng service để tạo media snapshot và không chỉnh lịch backup.

## Resource guard không đạt

10 mẫu chỉ đọc, 14:28:31–14:29:24 UTC+7:

- CPU trung bình 13,1%, peak 21%.
- RAM khả dụng thấp nhất 1.802 MiB.
- Disk queue peak **6**, ngưỡng preflight hiện có ≤2.
- Counter `Win32_PerfFormattedData_PerfOS_Memory.PagesInputPersec` peak **47.906**, ngưỡng hiện có ≤100. Giữ giá trị quan sát; chưa chứng minh nguyên nhân, chưa đối chiếu counter khác để kết luận paging là bottleneck.
- Ổ C: từ **9,61 xuống 9,48 GiB**, dưới ngưỡng ≥10 GiB.

Đây là inventory 10 mẫu, khoảng 53 giây giữa mẫu đầu/cuối, **không phải lượt preflight chuẩn 60 giây đạt**. Ngay cả các chỉ số vừa đo đã không đạt guard trong `scripts/preflight-evidence-vps.ps1` và `scripts/monitor-capacity-vps.ps1`; không cần chạy tải để tái hiện thiếu headroom. Không hạ ngưỡng hoặc dừng ứng dụng khác để ép qua.

Chỉ thống kê file trực tiếp, không quét recursive toàn ổ: `C:\DictaLab\backups` 0,272 GiB, `logs` 0,001 GiB, `staging` 0,464 GiB, `capacity` 0,100 GiB. Các số này **không bao gồm subdirectories**, không xác định tổng dung lượng hệ thống. Không đề xuất xóa backup/log/artifact chỉ vì chúng có thể giải phóng chỗ trống; artifact có thể cần cho rollback và bằng chứng.

## Phạm vi và bằng chứng

- SSH dùng key sẵn có, BatchMode/IdentitiesOnly/StrictHostKeyChecking; không đọc hoặc xuất private key.
- DB snapshot dùng app role, transaction `REPEATABLE READ READ ONLY`, statement timeout 10s, lock timeout 2s; credential chỉ trong environment process và được phục hồi sau lệnh. Không đọc learner content, email hoặc token; chỉ counts/version/table presence.
- Đọc whitelist service flags, executable path, hash JAR, backup file metadata và counters hệ điều hành. Không export toàn bộ service environment hoặc secret files.
- Không backup mới, restart/stop, deploy/migrate, tài khoản synthetic, ghi DB, Redis flush, RabbitMQ crash, gọi AI/TTS, truy cập S3 hoặc xóa file. Không đụng WebLens/Bilingual/ứng dụng khác.

Local evidence: `target/reliability-production-observation-20261009/observations.json` ghi lại các giá trị từ tool stdout; **bản chép lại, không giả là raw stdout gốc**. Các tool outputs gốc nằm trong lịch sử chat. `health-after.log` được ghi trực tiếp từ SSH stdout: 14:30:37 UTC+7, service Running/health UP, disk 9,48 GiB và JAR hash không đổi. File JSON hợp lệ, số tổng hợp được kiểm tra lại; encoding guard kiểm tra UTF-8 tài liệu. Không có test/backend build mới trong lượt này.

Ponytail giữ thay đổi ở báo cáo và evidence local, không thêm dependency/tooling production chỉ để vượt blocker. Các test local 166/166 mỗi PG và JavaScript 121/121 thuộc các batch trước, không phải số test production vừa chạy.

## Quyết định trước khi thử tiếp

1. Xử lý dung lượng/headroom. Muốn kiểm kê rộng hơn hoặc dọn cần chốt các thư mục thuộc phạm vi; trước khi xóa phải có danh sách file chính xác, bản sao cần thiết và phê duyệt. Không tự tăng tài nguyên trả phí.
2. Chốt cửa sổ bảo trì, thời gian gián đoạn tối đa và tiêu chí dừng/rollback. Lời cho phép thử production không được diễn giải thành quyền xóa dữ liệu thật hoặc kill DB/broker dùng chung.
3. Tách tài khoản/bài học/object synthetic bằng tên duy nhất; chỉ cleanup đúng resource do lượt thử tạo, không repair/xóa records người học để làm test xanh. Chốt trần S3/AI calls và chi phí trước khi gọi provider có phí.
4. Kiểm chứng backup/recovery S3 và tương thích release; không bỏ fail-closed gate mới để triển khai bản reliability local. Nếu chỉ kiểm tra endpoint không phá hủy thì kết luận giới hạn ở smoke, không gọi đó là chứng minh crash recovery.

Chưa deploy hoặc tắt HP: yêu cầu nghiệm thu toàn bộ vẫn chưa đạt. Không tạo automation/retry; chỉ tiếp tục thao tác có rủi ro sau khi điều kiện và phạm vi được chốt.
