# DictaLab — reliability accounting gate, 09/10/2026

Tiếp tục kế hoạch local, bỏ qua VPS theo yêu cầu. Không SSH, deploy, push/dispatch CI,
gọi AI/AWS, chạy tải mới, xóa evidence hoặc dừng dịch vụ khác.

## Phát hiện và sửa

**P1 — false-positive acceptance:** `scripts/load/verify-evidence.cjs` chỉ đọc
`journeys_completed` mà không đối soát; workload chỉ cần mỗi kind khác 0. Phép
`Math.max` trên string có thể tạo `NaN`, khiến so sánh `< target` không từ chối.
Rate ngoài miền 0/1 có thể nâng tỷ lệ success; hàm export không validate targets.
Hai caller trong `run-capacity-check.ps1` sử dụng exit code của CLI này, nên accounting
có thể được chấp nhận dù evidence mâu thuẫn. Đây là lỗi công cụ nghiệm thu, không
phải bằng chứng backend đã mất dữ liệu.

Replay cùng sáu fixture qua bản trước/sau sửa ghi nhận:

| Fixture | Trước | Sau |
| --- | --- | --- |
| Completed 0 nhưng 20 success samples | PASS | FAIL, `completed_journey_mismatch` |
| Tổng workload 21 nhưng started 20 | PASS | FAIL, `workload_count_mismatch` |
| Work window là string không phải số | PASS | Reject, `AssertionError` |
| VU sample là string không phải số | PASS | Reject, `AssertionError` |
| Một success sample = 2 | PASS, tỷ lệ 1,05 | Reject, `AssertionError` |
| VU target = NaN qua hàm export | PASS | Reject, `AssertionError` |

**P2 — lỗi input stream:** file `.gz` thiếu gây unhandled error ở ReadStream, không
đi qua catch của CLI. Sửa chuyển source error sang decoder, đóng stream trong finally;
file thiếu, JSON lỗi, gzip sai/truncated đều trả exit 1 với tên lỗi, không in raw payload.

Thay đổi nhỏ trên verifier có sẵn: validate finite/nonnegative numbers, safe integer
counters/VUs và overflow, binary success, known workload kind, safe integer targets;
đối soát completed = số success và tổng workload = started. Cập nhật expectation của
contract cũ; thêm `src/test/js/evidence-verifier.test.cjs`. Không dependency/API/schema/
Java/config runtime mới, không ép tỷ lệ mix quan sát thành tỷ lệ cấu hình.
CLI cũng trả một dòng lỗi kiểm soát cho tham số thiếu/sai, thay vì in stack trace.

Trade-off: raw thiếu metric/mâu thuẫn trước đây có thể PASS nay bị từ chối; phải giữ
artifact lỗi và thu lại evidence hợp lệ, không vá raw gốc. SLO vẫn ≥99,9%, không ép
100%: 999/1.000 thành công với 999 completed vẫn PASS; 998/1.000 FAIL.

## Test và tiêu chí nghiệm thu

- Red: **7 tests, 1 PASS/6 FAIL** trước sửa. Mỗi test nhóm dừng tại assertion đầu
  thất bại; không diễn giải sáu test đỏ thành tất cả input variants đã được chạy.
- Green đầu: **15/16**, một expectation cũ chưa bổ sung lý do completed mismatch;
  giữ log, cập nhật assertion để kiểm tra lý do mới, không bỏ validation.
- Green tiếp: **16/16**, verifier + load contracts; kiểm tra cả invalid metric domains,
  overflow, malformed tags, export targets, plain/gzip và controlled CLI errors.
- Final: **17/17** hai file test; toàn bộ JavaScript **131/131**, không fail/skip/cancel.
  Có test exit code 1 cho accounting/sai tham số và exit 0 tại đúng SLO 99,9%.
- Đính chính bàn giao: số 18/18 trong bản báo cáo trước là lỗi ghi số. Các CLI input
  variants nằm trong test hiện có, không tạo test mới; log targeted có 17 test.
- Sáu fixture false-pass trước sửa đều bị chặn sau sửa, evidence JSON lưu cả kết quả.
- Replay **raw smoke đã có**, không chạy lại workload: 176 started/finished/completed,
  success 1, peak 20 VU, window 101.088s, mix 93/73/7/3; accounting PASS.

Raw replay SHA256:
`028D16E4CFA8B9007221C7EC22387124435F3E99B4122BF2B335FED42B34D3AE`.
File nằm trong bundle nội bộ của batch trước; không sửa artifact này hoặc ghi đè
accounting cũ. Kết quả replay mới lưu riêng.

Lệnh tái hiện regression:

```powershell
node --test src/test/js/evidence-verifier.test.cjs src/test/js/load-contracts.test.cjs
node --test src/test/js/*.test.cjs
node scripts/load/verify-evidence.cjs <raw.json.gz> 20 60
```

Raw batch: `target/reliability-accounting-20261009-215429/`, gồm verifier trước sửa,
red/green/final logs, sáu fixture và `false-pass-replay.json`, smoke replay accounting,
source comparison và manifest SHA. Giữ raw nội bộ, không thay source ZIP/bundle cũ.

Đối chiếu 418 inputs của `ship-manifest.json`: chỉ verifier, expectation JS cũ và load
README đổi trong danh sách đó; test mới nằm ngoài snapshot cũ. **329 inputs Java/resources/tests/
POM không đổi SHA**, nên không build JAR hoặc chạy lại Testcontainers ở lượt này.
Các gate 170/170 trên PG17/18 là kết quả batch trước, không phải lượt chạy mới.

## Giới hạn còn mở

Gate này đối soát số liệu, không xác thực cryptographic provenance của k6 output,
không chứng minh concurrency duy trì bằng peak VU/max window, không thay thresholds,
resource guards hoặc DB invariants. Window smoke bao gồm drain; không gọi đó là tải dài.

P0 S3 object/cross-store recovery vẫn mở. P1 CI remote/production rollout/rollback và
P2 load/soak/stress trên topology mục tiêu chưa nghiệm thu. Bỏ qua VPS không mở release
safety gate. Không tắt HP vì chưa hoàn thành các operational gates.

Ponytail giữ sửa ở công cụ evidence đang có, không thêm hạ tầng hoặc viết lại backend.
Encoding Guard kiểm tra UTF-8 tài liệu/scripts/tests; kết quả và SHA lưu ở batch này.

## Chốt phạm vi local

Source và manifest trong batch accounting ban đầu thuộc thời điểm trước sửa CLI;
không ghi đè chúng để giả thành snapshot cuối. Bundle bàn giao bổ sung nằm trong
`target/reliability-handoff-20261009-v2.zip`, gồm source snapshot cuối, manifest và
bản sao log/evidence accounting. Archive có **436 file**, giải nén/hash verification
**436/436**, SHA256 `58B5FCE3ED1CB303A93D5A54D744D59ACF3B6696EF328B72492039B88DD50817`.
Không chứa `.env` thật hoặc production dump; dữ liệu test vẫn cần rà soát trên bản
sao trước công bố. Archive đầu tiên được giữ nguyên để truy vết thao tác copy lỗi;
không dùng làm bundle cuối.

Hoàn tất phần source/tests/evidence local đã thực hiện. Không đánh dấu toàn Giai đoạn
1 production-ready: các gate vận hành phía trên cần mục tiêu/budget/phạm vi được
phép và nằm ngoài yêu cầu bỏ qua VPS hiện hành. Không chạy shutdown khi mục tiêu
toàn bộ chưa được nghiệm thu; không phát sinh lịch tác vụ hoặc tiếp tục tải tự động.
