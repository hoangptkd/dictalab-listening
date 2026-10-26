# Thử nghiệm prompt tiếp theo cho full07 — 26/10/2026

## Mục tiêu và phạm vi

Tiếp tục sau `docs/full07-training-verification-backup-20261026.md`: thử giảm lỗi câu trả lời bằng prompt phiên bản mới, giữ nguyên base model và adapter checkpoint-200. Đây là inference phát triển; không huấn luyện, không chuẩn bị lại dữ liệu, không kích hoạt production.

- Local training giữ `PAUSED_BY_USER`; tập test không được đọc hoặc tokenize.
- Không sửa artifact đóng băng full07, dataset, split, hash hoặc bằng chứng lỗi cũ.
- Không đổi hàm dựng prompt lịch sử vì nó được dùng để kiểm tra hệ thống prompt/hash của các record cũ.
- Candidate riêng: `full07-inference-prompt-v2-20261026`, `status=development`, `releaseAllowed=false`.
- Toàn bộ script/prompt/raw output/metadata nằm ngoài Git tại `data/fine-tuning/colab-transfer-20261025/inference-prompt-v2-20261026/` trong checkout Desktop.

## Thay đổi prompt

1. Nội dung chỉ yêu cầu schema sáu trường `slots`, `utteranceEn`, `facts`, `questionVi`, `taskVi`, `skill`; không ghép instruction wire `targets` của teacher.
2. Quy tắc dịch chỉ rõ đủ bảy ngày trong tuần, ranh giới `by/before`, quan hệ anh/chị/em chưa rõ tuổi, modality và mã định danh.
3. Câu hỏi tiếng Việt phải giữ đúng hành động: nhận/lấy đồ đã có khác với đặt/mua trước.
4. Khi focus yêu cầu ý kiến, lời thoại phải có một nhận định cá nhân và lý do thực tế; lời giải thích cho quy định hoặc ý định không tự thay thế ý kiến.
5. Giữ giá trị đã chốt trong lịch sử; không tự gán hành vi cũ như chưa trả phụ phí. Điều kiện và lý do phải hợp lý với giờ hoạt động, không dựa vào ngoại lệ ngầm.
6. Giữ các kiểm tra độ dài, thứ tự câu hỏi/đáp án, evidence nguyên văn và không lộ đáp án.

Các quy tắc được viết theo loại lỗi, không chèn đáp án riêng của từng tình huống vào prompt hệ thống. Prompt dịch có bảng nghĩa ngày trong tuần đầy đủ; phép thử này đánh giá tuân thủ hướng dẫn có bảng tham chiếu, không đánh giá khả năng nhớ ngày không có trợ giúp.

## Thiết kế phép thử

- Sáu trường hợp × hai nhánh base/adapter, cùng đầu vào trong mỗi cặp.
- Bốn trường hợp regression đã biết: hai câu dịch, tạo A1 nhận vé, tạo B1 nhận chiếc đèn.
- Hai control tự soạn thêm: dịch việc nhận đồng hồ trước chiều thứ Bảy; B1 nhận máy ghi âm, lịch cũ thứ Hai và pin dự phòng đã chốt.
- Bốn regression dùng để đối chiếu prompt trước/sau; hai control chỉ có kết quả với prompt mới, không có baseline prompt cũ.
- Cùng revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a` và adapter SHA-256 `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`.
- CPU FP32, không quantization, seed17, greedy, không constrained decoding hoặc sửa câu trả lời sau khi sinh. Không thay môi trường hay cài dependency.
- Kiểm tra tự động JSON/schema/cấu trúc được tách khỏi việc đọc nghĩa, vai, continuity và tính hợp lý.

Đây là prompt được điều chỉnh sau khi đã xem lỗi regression. Sáu trường hợp nhỏ không tạo thành benchmark độc lập; hai control mới cũng không thay thế tập test.

Lượt inference đầu kết thúc khi đã lưu 10 đầu ra; handle thực thi không còn và PID đã kết thúc, chưa thu được exit code hoặc nguyên nhân. Giữ nguyên toàn bộ tệp gốc và `attempt-disposition.json`. Chỉ cặp `content-b1-control` còn thiếu được chạy riêng bằng cùng prompt/model/adapter/precision/seed trong thư mục `inference-prompt-v2-tail-20261026/`, có log và completion marker riêng. Đây là khôi phục phép thử inference, không phải train/resume. Không diễn giải việc mất handle thành lỗi GPU, quota hoặc optimizer.

## Đối chiếu các lỗi regression

| Trường hợp | Base với prompt mới | Adapter với prompt mới | So với lượt trước |
|---|---|---|---|
| Dịch deadline Wednesday | JSON hợp lệ nhưng dịch thành thứ Bảy; tự chọn chị | Dịch đúng thứ Tư nhưng vẫn chọn chị gái; fact mất nghĩa hạn chót `by` | Cải thiện định dạng của base và ngày của adapter; nghĩa deadline/độ tuổi vẫn chưa đạt |
| Dịch lời xin nhận đèn | JSON hợp lệ, giữ ngày và điều kiện; vẫn tự chọn anh trai | Giữ ngày/điều kiện nhưng tự chọn anh trai | Adapter hồi quy so với bản trước đã giữ “anh/em trai” |
| Tạo A1 nhận vé | 17 từ, đúng câu hỏi nhận vé và hai chi tiết/evidence | Đúng “nhận vé”, nhưng lời thoại 41 từ vượt tối đa 35 | Sửa lỗi nghĩa câu hỏi, đồng thời tạo hồi quy độ dài; không đạt đầy đủ |
| Tạo B1 nhận đèn | Có ý kiến/lý do, nhưng hỏi ý kiến người nghe thay vì người nói; câu hỏi điều kiện lộ sẵn trả phụ phí, có thêm phương án nhận today | Có ý kiến/lý do và giữ Friday, nhưng yêu cầu mang lại đèn đang chờ nhận; fact biến hóa đơn thành bắt buộc dù nhánh otherwise vẫn cho nhận đèn | Cải thiện biểu hiện focus, chưa giải quyết continuity và nghĩa câu hỏi/điều kiện |

Việc thêm chỉ dẫn không bảo đảm mọi yêu cầu được giữ đồng thời. Đầu ra được chấm nguyên bản; không sửa ngày, thay từ hay cắt lời thoại sau sinh.

## Control tự soạn thêm

| Trường hợp mới | Base | Adapter |
|---|---|---|
| Dịch nhận đồng hồ trước chiều Saturday | Đổi sang chiều Chủ nhật, tự chọn chị; “collection slip” thành “phiếu thu thập” | Giữ thứ Bảy, nhưng vẫn tự chọn chị gái và dịch “collection slip” thiếu tự nhiên thành “phiếu thu thập” |
| B1 nhận máy ghi âm, lịch Monday | 34 từ; hỏi ý kiến người nghe, dùng today không có ngày tham chiếu; động cơ nhân viên và việc khách nhận máy chưa hợp lý | 64 từ; hỏi đúng quan điểm nhân viên và giữ Monday, nhưng vẫn yêu cầu mang máy đang chờ nhận; fact biến giờ ưu tiên trước 16h thành điều kiện bắt buộc, dù lời thoại cho nhận hôm sau nếu đến muộn |

Các control không có baseline prompt cũ; không dùng chúng để tính mức cải thiện trước/sau. Lỗi ngày và cách dịch phiếu nhận đồ cho thấy một kết quả đúng trên regression không đủ chứng minh độ tin cậy ở tình huống khác.

## Tổng hợp kết quả

Đã lưu đủ **12 đầu ra duy nhất**, gồm 10 đầu ra của lượt gốc và 2 đầu ra của lượt bổ sung. Completion marker của lượt bổ sung là `COMPLETED`; nguyên nhân/exit code của lượt gốc vẫn chưa xác minh. Hai lượt khớp prompt, model revision, adapter, tokenizer, phiên bản thư viện, FP32 và seed. Đầu vào control cuối khớp nguyên vẹn giữa hai lượt.

| Kiểm tra tự động trên sáu trường hợp với prompt v2, mỗi nhánh | Base | Adapter |
|---|---:|---:|
| JSON nghiêm ngặt | 6/6 | 6/6 |
| Schema và cấu trúc bổ sung | 6/6 | 5/6 |
| Bị cắt bởi giới hạn token | 0/6 | 0/6 |

Adapter trượt cấu trúc bổ sung ở A1 vì 41 từ, vượt 35; JSON/schema riêng vẫn hợp lệ. Các tỷ lệ trên không phải tỷ lệ đúng ngữ nghĩa.

So sánh **chỉ bốn regression trùng nhau**, không đưa control vào mẫu số trước/sau:

| Kiểm tra | Base trước → mới | Adapter trước → mới |
|---|---:|---:|
| JSON nghiêm ngặt | 2/4 → 4/4 | 4/4 → 4/4 |
| Schema và cấu trúc bổ sung | 1/4 → 4/4 | 4/4 → 3/4 |

## Quyết định và bằng chứng

**Không chọn prompt v2 để thay thế; không chọn model để triển khai.** Mâu thuẫn wire đã được loại khỏi prompt thử nghiệm, một số biểu hiện đã tốt hơn, nhưng vẫn còn lỗi nghĩa và phát sinh hồi quy. Kết quả không chứng minh chỉ cần đổi prompt là giải quyết được độ tin cậy của adapter. Candidate giữ `development`, `releaseAllowed=false`; cấu hình ứng dụng không thay đổi.

- Script tự kiểm tra và biên dịch thành công; kiểm tra lại đủ 12 đầu ra, uniqueness, schema/cấu trúc và giới hạn token khớp các row đã lưu.
- Hash adapter và byte hash artifact đóng băng vẫn khớp trước/sau. Local training vẫn `PAUSED_BY_USER`; không có lần optimizer update/training nào được khởi chạy.
- SHA-256 `candidate-prompts.json`: `229925b005b512eb92a94d197bc963d9984d17556bda3aeb7a8d6b6950bb04f8`.
- SHA-256 kết quả gốc 10 row: `c5604c02e8be06de19d2311fc25e399d24367acd56ab1bca694059d548edc050`.
- SHA-256 kết quả bổ sung 2 row: `25326adf716dfd226c9bcb8ba7674e48798928955aa84539873bcb43572ebeaa`.
- SHA-256 `combined-results.jsonl`: `ceb905e5cf1f93ea30963c782356329d426326a2cacd1187d1dce42f8ca37055`.
- `combined-results.jsonl` là bản tổng hợp có nguồn rõ ràng, không thay thế các tệp gốc. `verification.json` liệt kê source file/source row cho cả 12 đầu ra; `manual-review.json` giữ nhận xét nghĩa cho sáu trường hợp.
- Các bằng chứng trên nằm ở thư mục private nêu đầu báo cáo và thư mục tail liền kề; model, raw output và credential không được đưa vào Git.

Kiểm tra script trên máy này mà không nạp model hoặc chạy inference mới:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-prompt-v2-20261026/compare_prompt_v2.py' --self-check
```

Giới hạn: phép thử dùng CPU và thư viện hiện có, không đối chiếu GPU và không dùng tập test. Chưa có đánh giá độc lập đủ lớn để chứng nhận chất lượng. Bằng chứng này chưa cho phép kết luận dataset huấn luyện sai hoặc quyết định huấn luyện lại; dataset không được mở để review trong công việc này.
