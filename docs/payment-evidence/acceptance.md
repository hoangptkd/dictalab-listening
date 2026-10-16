# Nghiệm thu Payment Operations

Ngày 17/10/2026 · Người kiểm tra: Codex (tự động). Tách bằng chứng local/CI với việc owner xác nhận nghiệp vụ thật.

| ID | Trạng thái | Bằng chứng và giới hạn |
|---|---|---|
| F01 | VERIFIED | `moneyAndCsvBoundariesRejectFractionsOverflowAndFormulaInjection`, rounding close, BIGINT migrations; số tiền integer, tỷ lệ BigInteger |
| F02 | VERIFIED | snapshot create/idempotency tests, CANCELLED/PAID regression; giá sửa qua config cho đơn mới |
| F03 | VERIFIED | 100 callback + lookup race, 100 refund approval race; một receipt/grant/reservation |
| F04 | VERIFIED | rollback partial posting, expired inbox lease, `PaymentRestoreIntegrationTest` pg_dump/restore thật |
| F05 | VERIFIED | API timeline/masked detail, receipts/journals/grants/refunds/audit; screenshots dữ liệu giả |
| F06 | VERIFIED | fresh PAID lookup, mất webhook, projection repair, exact reference và bank CSV overlap; không bao phủ chuyển ngoài order nếu thiếu bank feed |
| F07 | VERIFIED | PostgreSQL direct SQL từ chối unbalanced/posted mutation; service rollback và journalImbalances=0 |
| F08 | VERIFIED (simulated/manual) | refund tests + 100 approvals; UNKNOWN giữ tiền, attach evidence, independent confirm; không gọi payout thật |
| F09 | VERIFIED | content ADMIN bị từ chối finance; maker/checker khác; người confirm khác executor/importer; DB role riêng khi deploy |
| F10 | VERIFIED | published payOS HMAC vector + fake HTTP; ownership, CSRF, TOTP version binding/replay; decimal body bị từ chối |
| F11 | IMPLEMENTED | masked/no-store/export formula guard; deletion tách owner và giữ actor ID kiểm toán. Retention pháp lý cần owner rà soát; không tự xóa chứng cứ |
| F12 | VERIFIED (isolated) | provider retries/lease, actual backup/restore + missing provider event recovery, rollback compatibility test. RPO/RTO production chưa được cam kết |
| F13 | VERIFIED | balanced clearing/unallocated/deferred/revenue/refund accounts; cutoff/rounding/repeated close; baseline cũ không bịa doanh thu |
| F14 | IMPLEMENTED | support ticket liên kết, reason/audit/due dates, maker/checker decisions và owner status. Chưa công bố deadline/chính sách hoàn mới |
| F15 | VERIFIED (tests) | V39/V40 expand, repeat backfill, independent flags, release rollback compatibility, CI deployment checks; xem deployment evidence cho live |
| F16 | IMPLEMENTED | README/ADR/threat model/ASVS mapping, runnable fake UI và fault tests, screenshots desktop/mobile, kịch bản demo 6 phút. Chưa có video thuyết minh |

## Kết quả chạy đã có

- PostgreSQL 18.4: 31 tests, không fail/error/skip (18 operations + 7 billing + 6 provider), chạy từ schema rỗng.
- Testcontainers PostgreSQL 18.4 + Redis + Rabbit: 26 tests, không fail/error/skip; có `pg_dump`/`pg_restore` thật và phục hồi khoảng mất sự kiện.
- JavaScript billing: 3/3. Release tests: 6 pass; 1 filesystem POSIX test bỏ qua trên Windows, bắt buộc chạy trên CI Linux.
- Chrome/jev-ultrafast: đã mở 7 màn với dữ liệu giả; sửa tràn header/form mobile sau lần xem đầu. Bằng chứng cuối cần chụp lại sau CSS fix.
- Không tạo thanh toán/hoàn tiền thật trong toàn bộ bộ test này.

## Cổng production giữ đóng

`PAYMENT_REFUNDS_ENABLED=false`, `PAYMENT_PARTIAL_REFUNDS_ENABLED=false`. Automatic payout không được tích hợp. Owner chưa enrollment TOTP hoặc chỉ định checker độc lập trong phiên này; không giả xác thực danh tính thay owner.

Video 5–7 phút và kiểm tra sao kê ngân hàng thật là công việc cần người vận hành; đã có kịch bản demo và fixture tái lập. Không đánh dấu các hạng mục đó VERIFIED chỉ từ test giả.
