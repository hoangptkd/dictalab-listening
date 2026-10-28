# Tích hợp cải thiện hội thoại R13 — 28/10/2026

## Nguồn đã kiểm tra

Hội thoại **train AI conversation**, commit
`129ce65da9d707e542d97e04cda07d809c237eb9` trên nhánh
`codex/release-training-studio-20261025`, báo cáo
`docs/full07-inference-guided-pipeline-20261026.md`.
Candidate experiment có SHA-256
`fcdd5554cb0cf2364f4539138858be3014fc02a4379ded053533fad1af75c7b2`.
Đã chạy lại `verify_round13.py` trên bằng chứng riêng: PASS cho 32 đầu ra,
hash/manifest, token gốc, cấu trúc và thứ tự đóng băng lựa chọn.

Nghiên cứu GPU gốc ghi nhận 16/16 đầu ra hồi quy đạt nghĩa theo phép chấm
đã lưu. Với bốn câu mới, candidate base 4/4 và full07 4/4; baseline v2 đều
0/4. Toàn nghiên cứu có 308 đầu ra trên 30 tình huống được biên soạn, gồm
lượt lặp và candidate bị loại; không phải 308 trường hợp độc lập.
Chưa có duyệt độc lập của con người hoặc phê duyệt production.

## Phần áp dụng vào microservice

- AI worker thêm glossary từ chính câu tiếng Anh và từng fact; ưu tiên cụm
  dài và căn chỉnh thời gian/phủ định theo mệnh đề. Không sửa câu nguồn,
  fact hay câu trả lời do model sinh.
- Chỉ bật dịch R13 cho scene `delivery` / `Nhận hàng`. Scene khác giữ prompt
  dịch cũ. Quy tắc chìa khóa/bìa hồ sơ không áp dụng khi có ngữ cảnh máy tính,
  API hoặc bàn phím. Cần đánh giá thêm trước khi mở rộng phạm vi.
- Audit chặn thiếu thuật ngữ đã cấp, thay đổi mã định danh có phân biệt
  hoa/thường và ký tự CJK. Đây là kiểm tra hẹp, không chứng minh đúng nghĩa.
- Sinh nội dung nhận schema và khoảng số slot do server chọn: A1 đúng 2,
  A2/B1 từ 2 đến 3. Không áp dụng prompt sinh nội dung chuyên biệt giao nhận
  của nghiên cứu cho mọi scene.
- UI hiển thị số từ, giới hạn cứng và nhãn dịch R13. Chấp nhận kết quả vào
  bài luyện vẫn qua kiểm tra nghĩa/audio hiện có. So sánh chạy lần lượt
  base/full07, cùng đầu vào và không fallback.
- Không đổi model/base revision/adapter hash, không train lại hoặc sửa dataset.
  Artifact mới vẫn ở chế độ development/evaluation-only.

Artifact: `artifacts/prompts/conversation-demo-guided-r13-20261028.json`.
Hash ngữ nghĩa ghim đồng nhất ở Core và AI:
`62d4e1c7e03c26ebd9a5dca7d186bb27c22fd62be6078f09a72c2643402cf5cb`.
Artifact cũng khóa hash mã tạo guidance. Hash dùng văn bản UTF-8 với newline
chuẩn hóa để checkout Windows/Linux có cùng kết quả.

## Cutover và khôi phục

1. Chặn nhận job demo mới và chờ job đang xử lý kết thúc. Giữ database/SQLite
   và bản sao cấu hình bảo vệ trước khi cập nhật.
2. Build/restart Core, AI API và worker cùng phiên bản. Model Serving không
   đổi trọng số. Dùng `scripts/run-service.py` với file env riêng cho từng service.
3. Kiểm tra health, quyền truy cập, submit/poll và prompt hash trước khi mở demo.
   Job chờ có execution fingerprint cũ bị từ chối với `CONFIGURATION_CHANGED`;
   không âm thầm chạy bằng prompt mới.
4. Kết quả DONE lịch sử vẫn đọc được; áp dụng lại vẫn qua kiểm tra hiện có.
   Khôi phục bằng cả bộ Core/API/worker và cấu hình trước đó sau khi drain;
   không xóa ledger hay chạy down migration.

## Bằng chứng tích hợp và giới hạn

- Python: 350 tests PASS, 2 Redis tests tùy chọn bỏ qua trong môi trường local.
- Java: 65 tests, không lỗi/thất bại, 1 test tùy chọn bỏ qua.
- Bốn fixture tổng hợp đã quan sát trong nghiên cứu được so khớp chính xác;
  test kiểm tra không đổi nguồn, idempotence, phạm vi, hash drift, mã định danh,
  phủ định và từ chối câu vượt giới hạn cứng.
- Smoke Java/API/RabbitMQ/worker dùng provider giả kiểm tra scene cafe cũ và
  delivery có guidance, thứ tự base/base/full07/full07, số từ, provenance,
  duplicate delivery và phục hồi worker. Không phải phép chấm chất lượng model.
- Tokenizer thật trên prompt đại diện: input + cap 1.024 output là 2.882 token
  (A1), 3.048 (B1), 2.476 (dịch R13), dưới context cap 4.608. Server vẫn
  kiểm tra từng request thực tế.
- Chưa chạy generation CPU thật với pipeline mới: RAM trống thấp hơn điều
  kiện 20 GiB. Không hạ điều kiện bảo vệ.
- Kết quả CPU cafe lỗi schema trước đó thuộc pipeline cũ; không dùng làm
  bằng chứng đạt hoặc thất bại của R13. Cần chạy lại model thật khi đủ RAM.
- B1 có thể đạt giới hạn cứng 30–90 từ nhưng lệch mục tiêu mềm 45–55 từ
  (nghiên cứu có mẫu 35 từ). Không tuyên bố đã sửa hết lỗi độ dài hay adapter
  vượt base nói chung. Cải thiện model là mốc riêng, chưa deploy VPS.

Kiểm tra lặp lại từ gốc repository:

```powershell
python -X utf8 -m pytest -q services/ai-generation/tests services/model-serving/test_server.py tools/ai-eval
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationIntegrationTest,ModelDemoIntegrationTest,AiPlatformClientTest' test
python -X utf8 tools/ai-eval/jobs_smoke.py
node --check src/main/resources/static/js/model-demo.js
```
