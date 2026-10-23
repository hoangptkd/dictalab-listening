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
Đây là cấu hình offline. Lô đo thật đầu tiên có 6/8 bài đạt sau duyệt Codex (75%);
Web cho PASS cả 8. Mẫu nhỏ chưa xác nhận tỷ lệ dài hạn hoặc mốc 80%.

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
- Lô diagnostic v9 đã đo 8 bài mới, mỗi mức 2 bài: A1 2/2, A2 1/2, B1 2/2, B2 1/2.
  Không bị cắt token hoặc lỗi truyền; 2 bài Web PASS bị Codex loại vì lộ một phần
  đáp án giữa câu hỏi và thêm buổi chiều không được nguồn xác định. Tổng 75.530 token
  DeepSeek. Biên bản local: `data/fine-tuning/diagnostic-collection-v9-20261024/measurement-report.md`.
  Không tính 6 bài diagnostic này vào bộ chính.

Khi cần đo thật, dùng thư mục diagnostic mới; không lấy bài diagnostic bù dataset:

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-collection-v9-20261024 --diagnostic --artifact artifacts/prompts/conversation-reviewed-collection-v9-20261024.json --web-account account-1=http://127.0.0.1:3000/v1/chat/completions --web-account account-2=http://127.0.0.1:3001/v1/chat/completions
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-collection-v9-20261024 --limit 8
```

Sau mỗi lô phải duyệt độc lập theo quy trình đã chốt; Web PASS không tự thành approved.
Lịch tự chạy vẫn tắt cho tới khi chủ dự án yêu cầu bật lại.

## Tiếp tục bộ chính và giảm thời gian chờ

Chủ dự án yêu cầu sinh tiếp và tối ưu tốc độ ngày 24/10. Đã duyệt nốt 7 bài v8:
nhận 5, loại 2, tổng 156 bài chính; 267 ứng viên đã có quyết định. Bản v8 giữ
nguyên manifest/artifact. Chuỗi tiếp tục dùng thư mục mới với snapshot đầy đủ
quyết định, bằng chứng bài đạt và lịch sử gửi; không sinh lại bài cũ.

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/reviewed-700-v9-20261024 --continue-from data/fine-tuning/reviewed-700-v8-20261023 --artifact artifacts/prompts/conversation-reviewed-collection-v9-20261024.json --web-account account-1=http://127.0.0.1:3000/v1/chat/completions --web-account account-2=http://127.0.0.1:3001/v1/chat/completions
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/reviewed-700-v9-20261024 --limit 8 --overlap-web
```

- Không chạy lại prepare vào thư mục đã có. Bộ chính tiếp tục từ candidate-0268,
  cùng phân bổ nhóm nguồn/tập và trần **1.500 ứng viên cộng dồn**; 156 bài đã nhận
  được tính một lần. Không reset quota/cooldown của tài khoản khi nối phiên.
- `--overlap-web` chỉ chồng một lời gọi DeepSeek với một lượt Web, không chấm
  đồng thời nhiều bài trên cùng tab. Model, thinking, prompt và tiêu chí giữ nguyên.
  Lợi ích phụ thuộc thời gian hai nhà cung cấp; chưa có thử A/B để hứa phần trăm tăng tốc.
- Đợi lời gọi đang gửi hoàn tất trước khi thoát. Lỗi/quota dừng việc gửi mới;
  tối đa một bài kế tiếp đã gửi trong lúc lỗi Web chưa được trả về. Giữ nguyên bài đó.
  Khi hết quota rõ ràng, tiếp tục chấm bản đã lưu; không sinh lại hoặc tự replay UNKNOWN.
- Bài kế tiếp đã qua hard/dedup trước khi chờ Web trước, nên đường tiếp tục quota
  không bỏ qua dedup. Vẫn dừng sau mỗi lô để Codex duyệt độc lập, không tự nhận Web PASS.
- Snapshot giữ hash bằng chứng bất biến; sent-index chỉ được nối thêm sau tiền tố
  đã khóa. Export kiểm tra artifact gốc theo từng record, giữ nguyên prompt/messages,
  không gán bài v8 thành v9. Artifact bổ sung phải cùng schema, level và chính sách.
  Dedup/holdout/coverage và loss mask vẫn là các gate riêng khi chuẩn bị dataset.
- Sau đó chủ dự án đã trả lời rõ “Bật lại lịch, tiếp tục đến đủ 700 bài”; lịch đã
  được bật `ACTIVE`. Không train hoặc tắt máy trong giai đoạn này.

## Bổ sung Gemini song song theo yêu cầu mới

Chủ dự án yêu cầu dùng thêm các tài khoản Gemini với đúng model
`gemini-3.5-flash-lite`. Mã model đã đối chiếu [tài liệu Google](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite).
Registry hiện có ba nguồn Gemini `a`, `b`, `c`; giữ credential và quota trong registry.

Chiến dịch tiếp tục mới: `data/fine-tuning/reviewed-700-v9-mixed-20261024`.
Snapshot từ phiên v9 đã duyệt xong: 283 ứng viên cộng dồn, 167 bài đạt.
Không chạy prepare lại, sửa manifest hoặc gán lại model cho các bài kế thừa.

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/reviewed-700-v9-mixed-20261024 --continue-from data/fine-tuning/reviewed-700-v9-20261024 --gemini-teacher --artifact artifacts/prompts/conversation-reviewed-collection-v9-20261024.json --web-account account-1=http://127.0.0.1:3000/v1/chat/completions --web-account account-2=http://127.0.0.1:3001/v1/chat/completions
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/reviewed-700-v9-mixed-20261024 --limit 8 --overlap-web
```

- Hai lời gọi sinh tối đa: một DeepSeek, một Gemini. Pool Gemini luân phiên nguồn
  theo registry và admission hiện có; không reset quota hoặc tự chuyển model.
  Đổi nhà cung cấp bắt đầu giữa các lô để tránh gắn cố định một model vào một mức CEFR.
- Gemini dùng cùng prompt/schema, temperature 0,2 và giới hạn output/timeout của
  artifact. Gemini giữ thinking mặc định của nhà cung cấp; không gửi tham số
  `thinking` riêng của DeepSeek sang Gemini. DeepSeek vẫn thinking enabled.
- Một bài chỉ được sinh bởi một model. Lưu provider, source ID, model yêu cầu/thực trả,
  hash request/response và usage trong bằng chứng; không trộn hai phản hồi thành một bài.
- Web tiếp tục một yêu cầu mỗi lúc, chồng thời gian với sinh. Codex vẫn duyệt
  độc lập, căn cứ từng fact và từng lỗi Web bị bác; Web PASS không tự nhận.
- Đặt chỗ trước cho các ô mức/tập đang sinh, giữ trần 1.500 ứng viên cộng dồn.
  Khi dừng, đợi và lưu phản hồi đã gửi từ cả hai luồng, không hủy hay replay.
- DeepSeek hết quota/số dư hoặc pool0: dừng toàn bộ, báo cáo và tạm dừng lịch.
  Gemini HTTP402/429 hoặc quota không nhận thêm: giữ checkpoint, dừng lô và báo cáo;
  không gọi lại bài lỗi. UNKNOWN của một nguồn chặn gửi mới và cần đọc bằng chứng.
- Không lấy bài diagnostic bù, không đổi tiêu chí nhận bài, không train/deploy/tắt máy.

## Kiểm tra không gọi model

```powershell
python -X utf8 -m pytest training/tests services/ai-generation/tests/test_provider_registry.py services/ai-generation/tests/test_model_gateway.py -q --disable-warnings
python C:/Users/HP/.codex/skills/vietnamese-encoding-guard/scripts/check_encoding.py training/collect_reviewed.py training/freeze_candidate.py training/iterate_teacher.py training/tests/test_collect_reviewed.py artifacts/prompts/conversation-reviewed-collection-v9-20261024.json docs/conversation-collection-v9-improvements-20261024.md
git diff --check
```
