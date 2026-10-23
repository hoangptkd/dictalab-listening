# Cải thiện thu thập v9 — 24/10/2026

## Đã dừng lịch chạy

Automation `ti-p-t-c-thu-th-p-700-b-i-duy-t` đã chuyển sang `PAUSED` theo yêu cầu.
Lô v8 đang gửi được kết thúc bình thường để giữ kết quả, không khởi chạy lô khác.
Checkpoint hiện có 267 ứng viên, 151 bài duyệt và 7 bài chờ Codex duyệt độc lập.
151 bài gồm 105 train, 23 validation, 23 test. Không xóa hoặc sửa các quyết định cũ.

## Bằng chứng dùng để sửa

Mốc phân tích cố định: 259 ứng viên đầu đã có quyết định, trước lô đang chạy.
Toàn bộ mốc đó đạt 151/259 (58,3%). Việc chọn sửa chỉ dùng các nhóm lỗi
train/validation; không đọc nội dung holdout test để chọn prompt hoặc checkpoint.

- Train/validation: 128/223 đạt (57,4%).
- 23 ứng viên train/validation bị cắt: usage cho thấy cả 23 dùng hết 8.192
  completion token, với trung vị reasoning token cũng là 8.192. Trần hiện tại
  có thể hết ngay khi model chưa xuất nội dung; đây là lỗi vận hành riêng với lỗi ngữ nghĩa.
- Các nhóm lỗi còn lại: lộ đáp án trong lời giao bài/câu hỏi khác; dịch mất độ
  chính xác hoặc thêm thuộc tính; scope câu hỏi/đáp án không khớp; lặp ý đã kiểm tra;
  bằng chứng suy luận đặt sai loại bài. Judge đôi lúc bỏ sót hoặc bác B2 thiếu căn cứ.
- Một lỗi đọc kết nối và bốn timeout liên tiếp trong lô trước cho thấy cần dừng
  khi kết quả truyền chưa rõ, thay vì tiếp tục tiêu ứng viên trong cùng sự cố.

## Thay đổi

Artifact mới: `artifacts/prompts/conversation-reviewed-collection-v9-20261024.json`.
Đây là cấu hình phát triển offline, chưa có tỷ lệ đạt đo bằng model thật.

1. Rút gọn prompt sinh; giữ quy tắc nguồn, vai, lịch sử, novelty, trạng thái thông tin,
   scope đáp án, số lượng, CEFR và độ dài. Không giảm tiêu chí nhận bài.
2. Wire schema cố định `taskVi` thành “Nghe và trả lời các câu hỏi.” để hướng dẫn
   không tiết lộ mục đích/ý chính hay dịch sai vai người phục vụ.
3. Kiểm tra độc lập giữa mọi câu hỏi; cấm tiết lộ cả một thành phần của đáp án khác.
4. Bắt buộc reasoningEvidence cho inference/argument; cấm trường đó ở các loại
   mà validator không cho phép. Mọi trích dẫn vẫn phải là đoạn nguyên văn.
5. Dịch từ bản Anh cuối cùng; giữ mốc chính xác như “12 giờ trưa”, điều kiện,
   lượng, phủ định và nghĩa từ theo ngữ cảnh. Không tự thêm chất liệu/vị trí/giới tính.
6. Bổ sung phương pháp chấm được khóa theo artifact: kiểm tra UI, dịch, toàn bộ
   quy trình được hỏi và lựa chọn có điều kiện; chấp nhận lập luận B2 được nói rõ
   khi câu hỏi thật sự kiểm tra quan hệ đó. Rubric và ngưỡng nhận giữ nguyên.
7. Riêng artifact mới: trần 16.384 token, timeout 240 giây; model và thinking giữ nguyên.
   Đây là tăng giới hạn, có thể tăng chi phí/thời gian, không bảo đảm hết lỗi cắt.
   Artifact v8 vẫn dùng 8.192 token và timeout cũ. Không gọi lại ứng viên đã gửi.
   API DeepSeek mô tả `finish_reason=length` là chạm giới hạn output/context;
   xem [tài liệu API chính thức](https://api-docs.deepseek.com/api/create-chat-completion/).
8. Lỗi truyền DeepSeek chưa rõ kết quả dừng batch ở `BLOCKED`; giữ hash/trạng thái
   gửi, xóa chi tiết bài loại như trước. Duyệt bài cũ không tự xóa trạng thái BLOCKED.

## Tương thích và bước đo tiếp

- Giữ nguyên artifact, manifest, rubric, validator và 151 bài duyệt của v8.
- Lệnh `prepare` mới mặc định chọn v9; `collect` chiến dịch cũ vẫn đọc artifact đã khóa.
  Không `prepare` lại thư mục cũ hoặc sửa hash để thay prompt giữa chiến dịch.
- Chưa gộp các chiến dịch hoặc xuất dữ liệu train. Nếu thu thập nhiều phiên bản,
  phải kiểm tra phân bổ, provenance, dedup và holdout giữa chúng trước khi xuất.
- Kiểm tra hiện tại dùng schema và mock; không có lời gọi DeepSeek/Web mới để đo v9.
  Không tuyên bố tăng tỷ lệ đạt trước khi có kết quả mới được Codex duyệt từng bài.

Khi cần đo thật, dùng thư mục diagnostic mới; không lấy bài diagnostic bù dataset:

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-collection-v9-20261024 --diagnostic --artifact artifacts/prompts/conversation-reviewed-collection-v9-20261024.json --web-account account-1=http://127.0.0.1:3000/v1/chat/completions --web-account account-2=http://127.0.0.1:3001/v1/chat/completions
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-collection-v9-20261024 --limit 8
```

Sau mỗi lô phải duyệt độc lập theo quy trình đã chốt; Web PASS không tự thành approved.
Lịch tự chạy vẫn tắt cho tới khi chủ dự án yêu cầu bật lại.

## Kiểm tra không gọi model

```powershell
python -X utf8 -m pytest training/tests services/ai-generation/tests/test_provider_registry.py services/ai-generation/tests/test_model_gateway.py -q --disable-warnings
python C:/Users/HP/.codex/skills/vietnamese-encoding-guard/scripts/check_encoding.py training/collect_reviewed.py training/freeze_candidate.py training/iterate_teacher.py training/tests/test_collect_reviewed.py artifacts/prompts/conversation-reviewed-collection-v9-20261024.json docs/conversation-collection-v9-improvements-20261024.md
git diff --check
```
