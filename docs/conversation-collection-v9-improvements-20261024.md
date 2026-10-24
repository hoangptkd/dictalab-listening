# Cải thiện thu thập v9 — 24/10/2026

## Mới nhất: pilot effort A1/A2 và hai lời gọi DeepSeek

Đã thử thật 16 ứng viên diagnostic, ngoài SFT và ngoài trần ứng viên của bộ chính:
8 cấu hình thinking mặc định (`high`) và 8 `reasoning_effort=low`, mỗi cấu hình có
4 A1 và 4 A2. Tám cặp dùng cùng context, wrapper, model `deepseek-flash`, schema,
trần 16.384 token, timeout 240 giây và rubric. Hai nửa dùng các biến thể context
khác nhau; thứ tự cohort là high/1, low/2, high/2, low/1. Chỉ thay effort trong
từng cặp, không dùng cắt trần token để ép suy nghĩ ngắn. Bốn ô nguồn/mức thuộc ba
họ kịch bản, tám context, đều train; không dùng nội dung holdout để chọn cấu hình.
Không thử Gemini.

Codex đọc nguồn, vai, lịch sử và toàn bộ trường song ngữ trước khi đọc verdict Web,
ghi căn cứ riêng và quote mỗi fact rồi chạy `apply-independent-notes.py`. Web cho
PASS cả 16; Codex loại một bài A2 low do câu hỏi khác tiết lộ một waypoint bắt
buộc trong đáp án lộ trình. Đã xóa chi tiết bài loại và raw nhóm dùng chung;
giữ hash, loại lỗi, usage và bằng chứng gửi, không sửa hoặc đưa bài diagnostic vào SFT.

| Effort | Codex đạt | A1 | A2 | Completion token | Reasoning token | Sinh trung bình/bài |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Mặc định high | 8/8 | 4/4 | 4/4 | 33.817 | 31.175 | 22,32 giây |
| Low | 7/8 | 4/4 | 3/4 | 22.480 | 19.857 | 15,97 giây |

Low giảm 33,5% completion token, 36,3% reasoning token và 28,4% thời gian gọi
trung bình trong mẫu này. **Chưa áp dụng low vào bộ chính**: chưa giữ được tỷ lệ
đạt quan sát. Mẫu 8 bài/mỗi effort nhỏ, không chứng minh khác biệt dài hạn hay
quan hệ nhân quả của một lỗi đơn lẻ. Không đổi artifact/manifest/hash của chiến dịch.

| Cohort 4 bài | In-flight tối đa | Sinh + Web, chưa gồm Codex |
| --- | ---: | ---: |
| High tuần tự | 1 | 123,20 giây |
| High song song | 2 | 76,18 giây |
| Low tuần tự | 1 | 109,63 giây |
| Low song song | 2 | 57,90 giây |

Đây là thời gian mô tả: cohort 1/2 khác biến thể/focus và output, còn cache/tải API
cũng thay đổi. Không coi chênh lệch high 38,2% là cam kết tăng tốc; không hứa gấp đôi.
Tất cả 16 lượt DeepSeek HTTP 200, đủ usage, không 402/429/UNKNOWN. Hai cohort song
song ghi nhận peak=2; request được lưu trước HTTP, mỗi ID chỉ gửi một lần. Tám nhóm
Web dùng tám hội thoại mới của account-1, model UI chưa xác minh; token Web không có.
Web vẫn một request mỗi lúc, Codex duyệt từng bài. Tổng teacher usage pilot:
76.468 input + 56.297 output = 132.765 token, gồm 51.032 reasoning token trong output.

Đã thêm tùy chọn **`--teacher-concurrency 2`** và giữ mặc định cũ nếu không truyền.
Tái sử dụng reserve ID/ô đích, quota admission atomic, kiểm tra trùng nối tiếp,
guard chống replay và drain mọi sibling/Web đang gửi khi lỗi. Kiểm tra mock gồm
sibling lỗi trả trước, quota, UNKNOWN, exception, trùng, trần 1.500 và ô cuối cùng.
Kiểm tra hoàn tất: 298 test đạt, 1 bỏ qua; UTF-8, compile và `git diff --check` đạt.
Theo [DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/),
effort low được hỗ trợ và mặc định là high; [giới hạn API công bố](https://api-docs.deepseek.com/quick_start/rate_limit/)
cho `deepseek-flash` là 2.500 đồng thời/tài khoản. Đây không bảo đảm số dư hoặc
khả năng nhận request thực tế; giữ quota pool/cooldown, dừng trên quota hoặc lỗi chưa rõ.

Quyết định vận hành: **high như cũ + DeepSeek song song 2**, Web theo cặp và Codex
độc lập. Lịch 15 phút được tạm dừng khi test, bật lại sau kiểm tra và delivery.
Checkpoint bộ chính vẫn **244/500 duyệt, 411/1.500 ứng viên**, không còn pending.
Không prepare lại, không tính 15 bài diagnostic đạt vào bộ chính. Không shutdown
hoặc arm watchdog. Đủ 500 và audit thật đạt mới SFT; không deploy.

```powershell
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/reviewed-500-v9-deepseek-20261024 --limit 8 --deepseek-only --teacher-concurrency 2 --web-batch-size 2 --overlap-web
```

Bằng chứng riêng local: `data/fine-tuning/diagnostic-speed-20261024/results.json`,
timing, notes và quyết định trong từng cohort. Cấu hình low chỉ nằm trong artifact
diagnostic; `teacherGenerationConfig.reasoningEffortByLevel` được khóa cùng manifest
và kiểm tra cả body HTTP. Chưa có thay đổi cấu hình sinh cho B1/B2.

## Cập nhật mới nhất: hủy tắt máy và tiếp tục thu thập

Chủ dự án yêu cầu hủy tắt máy và tiếp tục tạo bài. Lệnh đang chờ đã được hủy
thành công bằng `shutdown.exe /a`. Từ đây không dùng
`--shutdown-on-worker-failure`, không arm watchdog tắt máy và không tự gọi shutdown,
kể cả khi quota hoặc lỗi chặn thu thập. Lưu checkpoint và báo lỗi khi không thể tiếp tục.
Các quyền tắt máy trong phần lịch sử bên dưới đã bị thay thế.

Checkpoint khôi phục có 236 bài duyệt/395 ứng viên. Hai bài cuối có lượt Web
`UNKNOWN`, không có phản hồi lưu được; không gửi lại. Sau khi kiểm tra không còn
worker hoặc bridge đang xử lý, có thể chốt loại vận hành cả nhóm: giữ hash,
account, usage và trạng thái `UNKNOWN` cùng quyết định bỏ, xóa nội dung chi tiết.
Không tạo Web PASS hoặc nhận bài chưa chấm để gỡ chặn. Tiếp tục bằng ID ứng viên mới,
giữ nguyên manifest, trần ứng viên, quota và các bài đã duyệt.

Lỗi checkpoint `WinError 5` được xử lý bằng retry ngắn, có giới hạn cho lỗi chia sẻ
file Windows; giữ bản cũ và file tạm nếu lỗi vẫn còn. Không bỏ atomic replace.
Mục tiêu 500 bài, DeepSeek-only, Web theo cặp và Codex duyệt độc lập vẫn giữ nguyên.

## Quyết định mới nhất: 500 bài, DeepSeek và chấm Web theo cặp

Chủ dự án đã xác nhận **400 train + 48 validation + 52 test**, mỗi mức A1/A2/B1/B2
lần lượt **100/12/13**. Tổng là 500 bài, không phải 500 train cộng tập khác.
Chiến dịch đang dùng: `data/fine-tuning/reviewed-500-v9-deepseek-20261024`.
Snapshot giữ nguyên 185 bài duyệt/311 ứng viên từ phiên mixed; không loại bỏ ba bài
Gemini đã duyệt, không gọi Gemini mới, không sửa manifest hoặc reset quota của phiên cũ.
Trần 1.500 ứng viên vẫn cộng dồn. Những mục 700/mixed bên dưới là lịch sử.

Đã thử thật trong **ba hội thoại mới khác nhau** của account-1:

| Nhóm | Mẫu | Thời gian Web | Kết quả đối chiếu độc lập |
| --- | --- | ---: | --- |
| 3 bài | A1/A2/B1 validation đã chấm riêng | 25,16 giây | Đủ 8 fact audit; có một lỗi giả `noAnswerLeak` ở bài A2 |
| 2 bài | Một bài tốt và một bản diagnostic cố ý dịch sai thời lượng | 15,37 giây | Bài tốt giữ kết quả; lỗi dịch được phát hiện đúng |
| 2 bài | Hai bài A2/B1 validation gốc | 18,55 giây | Khớp toàn bộ checks chấm riêng và duyệt độc lập |

Lỗi giả nhóm 3 cho rằng câu hỏi “giấy tờ gì” tiết lộ đáp án “phiếu đăng ký”.
Câu hỏi không nêu loại giấy tờ; đáp án gist chỉ nói chào đón và hướng dẫn nhận phòng,
không đòi liệt kê loại phiếu. Vì vậy cảnh báo không có căn cứ. Không dùng nhóm 3 tiếp.
Mẫu sai diagnostic được tách riêng, không sửa bản gốc và không đưa vào SFT.
Biên bản đầy đủ local: `data/fine-tuning/diagnostic-web-batch-20261024/`.
Nhãn model Web không được UI cung cấp; không coi alias là model đã xác minh.

Đã chạy tiếp lô thật DeepSeek-only candidate-0308–0311: hai hội thoại Web mới,
đủ 11 fact audit, cả 4 bài được Codex duyệt độc lập sau khi đọc nguồn trước Web.
Mẫu nhỏ không chứng minh tỷ lệ dài hạn hoặc phần trăm tăng tốc. Quyết định vận hành:
**2 bài/hội thoại mới**, một yêu cầu Web mỗi lúc; `--overlap-web` vẫn chồng sinh và chấm.

```powershell
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/reviewed-500-v9-deepseek-20261024 --limit 8 --deepseek-only --web-batch-size 2 --overlap-web --shutdown-on-worker-failure
```

Không prepare lại thư mục này. Chấm nhóm giữ schema/checks/evidence riêng từng bài,
hash request/response chung và liên kết thành viên. Thiếu/trùng ID, trích dẫn chéo bài,
JSON sai hoặc kết quả gửi chưa rõ đều chặn; không tự gửi lại riêng thành viên.
Quota rõ ràng mới cho phép tiếp tục đúng request nhóm đã lưu. Bài lẻ cuối lô được
chấm riêng. Bài loại xóa raw request/response/attempt dùng chung có chứa chi tiết bài
đó; giữ metadata hash/account/usage/trạng thái và bằng chứng riêng của bài đạt.

### SFT và quyền tắt máy mới

Đủ **500 bài duyệt**, không còn pending: audit phân bổ 100/12/13 mỗi mức, provenance,
exact/near duplicate, holdout và cặp content/translation; export. Không tự gắn gate
audit đạt khi chưa kiểm tra, không train dữ liệu thiếu/chưa duyệt. Sau audit đạt,
chạy SFT/LoRA thật Qwen3.5-4B bằng runner hiện có, chọn checkpoint bằng validation.
Config local `training-config.yaml` trong chiến dịch mới trỏ vào export/dataset và
training-review.json. Dùng Python `.venv-cpu`, giữ loss mask và quyền thử dưới RAM
ước lượng bằng `--attempt-below-memory-estimate`; không chạy smoke tài nguyên riêng.
Lưu optimizer step/checkpoint/loss hoặc lỗi thật; không deploy production.

Chủ dự án đã cấp lại quyền **tắt máy khi hết quota hoặc không thể tạo thêm dữ liệu**,
không hỏi lại. Web một tài khoản hết quota vẫn chuyển đúng quy tắc; cả hai hết quota
thì lưu checkpoint/báo cáo và tắt máy. Không chờ quota qua đêm như chỉ dẫn cũ.
Không tắt chỉ vì đủ bài: phải chuyển sang audit/SFT. Khi thất bại không thể tiếp tục,
lưu kết quả/lỗi và usage biết được/phần thiếu, dừng automation, flush file rồi gọi
`shutdown.exe /s /f /t 60`. Không dùng lệnh thật trong kiểm thử.

Watchdog ngoài worker đã triển khai: chờ worker lưu/drain các lời gọi đã gửi rồi
xử lý trạng thái quota/BLOCKED/cap hoặc worker thoát bất thường. Duyệt Codex giữa
lô là trạng thái bình thường. Watchdog này không giám sát việc Codex hết token,
Codex bị treo hoặc Windows treo; prompt/lịch không bảo đảm xử lý các tình huống đó.
Các mục hủy quyền tắt máy trước đây được thay thế bằng yêu cầu mới này.

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
