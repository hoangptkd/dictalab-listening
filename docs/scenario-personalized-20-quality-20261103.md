# Kiểm tra thực tế 20 bài cá nhân hóa — 03/11/2026

## Kết luận

**Chưa đạt chất lượng để công nhận cả pipeline đã ổn định.** Đã chạy AI thật trên đủ 20 đầu vào gốc: 11 bài qua gate (55%), 9 bị từ chối (45%). Không có trường hợp chưa chạy, mất kết quả hoặc bị dừng vì HTTP/quota.

**55% là tỷ lệ qua gate, không phải tỷ lệ bài đúng.** Đọc độc lập output cho thấy một số bài được chấp nhận vẫn sai đáp án, gộp câu hỏi, gắn sai kỹ năng hoặc mất vai người học. Những nhận xét nội dung dưới đây là đánh giá của agent, chưa phải nhãn chuẩn do người dùng/giáo viên xác nhận.

Lượt này chỉ đo phiên bản hiện tại; không sửa prompt, validator, cấu hình chương trình hay code production, không tự sinh lại bài bị từ chối. Không so tỷ lệ trực tiếp với V4/V5/few-shot cũ vì chương trình và độ khó đã thay đổi.

## Thiết lập đã đóng băng

- Source commit: `6be0ad72fa3fcdaeaa1756a629319ada59cb4aba`.
- Prompt: `SCENARIO_PERSONALIZED_V1_GRADE_V4`.
- Prompt/schema pin: `bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa`.
- Corpus: giữ nguyên 20 `inputVi`/ID trong `data/e20/cases.json`, nguồn fixture `services/ai-generation/tests/fixtures/evaluation/scenario-prompt-paired-20-20261102.json`.
- Corpus cùng snapshot cá nhân hóa SHA256: `2e97ea66d12b92f925bead9d7541e3a0661c4e00f4923d778d64035fa34acf0a`.
- Model: `gemini-3.5-flash-lite`, temperature 0; không chạy đối chiếu model hoặc train mới.
- Registry thực tế có **bốn nguồn đang bật `a,d,b,c`**, dùng nguyên registry hiện tại. Số lần thành công: 24/24/24/23. Không sửa hay thêm API key.
- 8 bài giao tiếp program v3, đủ CEFR A1–C2; 6 TOEIC Listening v2, mục tiêu 200/350/450; 6 IELTS Listening v2, mục tiêu 4.5/6.5/8.
- Bài mới dùng `currentValue=null`, `currentProvenance=UNKNOWN`, `focusSkill=main-idea` như luồng hiện tại. Mô tả có thể cung cấp thêm mục tiêu nghe riêng.
- Trình tự: EXTRACT → GENERATE chỉ hội thoại → QUESTIONS gồm dữ kiện/câu hỏi/đáp án/bằng chứng → REVIEW_QUESTIONS → TRANSLATE → REVIEW. GRADE vẫn giữ V4 nhưng không được gọi trong lượt sinh bài này.
- Với phép thử tổng hợp, tự chấp nhận specification/giả định từ EXTRACT để đi tiếp; không mô phỏng việc người dùng sửa/xác nhận bản nháp.
- Một lượt mỗi bài. Chỉ cho phép đổi nguồn khi gặp HTTP 429/503 có kết quả rõ và giữ cooldown; thực tế không phát sinh retry. Không gọi lại lỗi chất lượng hoặc kết quả không rõ.
- Thời gian: 00:16:55–00:22:32 ngày 03/11/2026, UTC+7; tương ứng 17:16:55–17:22:32 ngày 02/11 UTC.

## Kết quả theo chương trình

| Chương trình | Số bài | Qua gate | Bị chặn |
| --- | ---: | ---: | ---: |
| Giao tiếp CEFR | 8 | 5 | 3 |
| TOEIC Listening | 6 | 3 | 3 |
| IELTS Listening | 6 | 3 | 3 |
| **Tổng** | **20** | **11** | **9** |

Đây là chương trình luyện nội bộ DRAFT, chưa hiệu chuẩn sư phạm. Kết quả không chứng minh bài tương đương band IELTS, điểm TOEIC hoặc mức CEFR ghi trong mục tiêu.

## Những gì thực sự đã sinh

| Bước | Output model đã lưu | Ghi chú |
| --- | ---: | --- |
| EXTRACT | 20 | Cả 20 qua kiểm tra kỹ thuật; vẫn có lỗi ngữ nghĩa về vai |
| GENERATE hội thoại | 20 | 4 bị chặn ngay vì thiếu từ |
| QUESTIONS | 16 | Giữ nguyên output kể cả bài bị chặn ở bước sau |
| REVIEW_QUESTIONS | 15 | 1 output sai schema; 2 bị gate nội dung câu hỏi chặn |
| TRANSLATE | 12 | Không dịch những bài đã bị gate trước đó chặn |
| REVIEW | 12 | 1 bị chặn vì bản dịch sai chính tả |
| **Tổng gọi AI** | **95** | Tất cả HTTP thành công; không 429/503 |

Usage provider ghi nhận: 172.095 prompt tokens + 66.825 completion tokens = 238.920 tokens. Không suy ra chi phí tiền từ số token khi chưa đối chiếu giá/thanh toán.

Không có 20 bài hoàn chỉnh được chấp nhận: có **20 hội thoại thô, 16 bộ câu hỏi, 12 bộ dịch, 11 bài qua toàn bộ gate**. Không điền giả phần còn thiếu và không bỏ gate để đủ số lượng.

## Chín bài bị chặn

| Nhóm lỗi quyết định | Số bài | Bằng chứng |
| --- | ---: | --- |
| Thiếu từ thực tế | 4 | case-03 a1: 29 < 30; case-06 a3: 38 < 60; case-12 a3: 39 < 45; case-18 a3: 42 < 45 |
| Schema reviewer không hợp lệ | 1 | case-09 `questionChecks[5].reasonVi` vượt 300 ký tự |
| Câu hỏi có nhiều đơn vị đáp án | 2 | case-11 a2/q2: biểu đồ + văn bản; case-14 a2/q1: thời lượng + tần suất |
| Dịch tiếng Việt sai chính tả | 1 | case-13: “chúo”, “thư viền”, “sinh viãn”, “thối gian”, “nàn” |
| Evidence agreement không đủ | 1 | case-20 a3/q2 chỉ trích một người; gate yêu cầu proposal/acceptance đúng thứ tự của hai người trong cùng activity |

Ở case-20, hội thoại có đề xuất ở activity trước và phản hồi đồng ý ở phần cuối. Gate hiện yêu cầu bằng chứng cục bộ trong từng activity. Do đó đây còn là giới hạn hợp đồng cần xem xét riêng; không đồng nhất với việc hội thoại hoàn toàn vô lý.

## Lỗi nội dung còn lọt qua gate

| Bài | Vị trí | Vấn đề có thể kiểm tra lại trong output |
| --- | --- | --- |
| 01 — A1 | a2/q2 | Hỏi nguyên liệu nhưng gắn `skill=reason`; reviewer vẫn đánh phù hợp kỹ năng |
| 02 — A2 | a1/q1 | Transcript “picnic”, đáp án “đi cắm trại”; bản dịch segment lại đúng là “dã ngoại” |
| 05 — C1 | a1/q1, a3/q1 | Hỏi “ở đâu và để làm gì”, “mấy giờ và ở đâu”, nhưng reviewer gộp nhiều thông tin vào một chuỗi và cho atomicMeaning=true |
| 08 — TOEIC 350 | a1/q1 | Hỏi cả món đồ và người nhận quà; hai yêu cầu độc lập trong một câu |
| 10 — TOEIC 200 | a3/q2 | Công việc dự định làm ngày mai nhưng đáp án cảm ơn vì “đã hoàn thành” |
| 15 — IELTS 8 | a2/q1 | Hỏi cả trạng thái dây cáp và má phanh; reviewer gom thành một đơn vị đáp án |
| 16 — IELTS 4.5 | EXTRACT, a2/q1–q2, a3/q2 | “Tôi gặp hàng xóm” bị gán listener; hai câu hỏi cùng bốn giờ nắng; hỏi loại cây nhưng gắn purpose |
| 17 — IELTS 6.5 | EXTRACT, câu hỏi a1 | “Tôi muốn mời bạn” mất vai người mời; câu hỏi dùng ID Char-1/Char-2 thay cho tên |

Đây là tám bài qua gate có ghi chú vấn đề từ lần đọc của agent, **không phải tỷ lệ false acceptance đã hiệu chuẩn**. Mức nghiêm trọng khác nhau: sai đáp án, mất vai, câu hỏi nhiều yêu cầu và sai nhãn kỹ năng không nên gom thành một điểm số chất lượng giả chính xác.

Các vấn đề khác trong bài bị chặn:

- case-11: input là học viên/bạn cùng lớp nhưng EXTRACT chuyển sang đồng nghiệp/công ty. Thích ứng TOEIC đã lấn át dữ kiện người dùng; bài này bị chặn ở câu hỏi nên REVIEW nguồn/vai chưa chạy.
- case-09: đổi hai người cùng chọn hoa thành khách/nhân viên; câu hỏi hỏi thời hạn gửi báo giá trong khi cụm hạn ở nguồn gắn với giao hàng. Reviewer vẫn `valid=true` nhưng lỗi schema ngăn bài được chấp nhận.
- case-14: câu đã nói trực tiếp “I will fill out the registration form right now” lại được gắn supported-inference; reviewer không bắt lỗi nhãn này, dù đã bắt câu hỏi gộp khác.

case-04 và case-07 chưa thấy lỗi nội dung nghiêm trọng trong lần đọc này. case-19 có diễn biến phân công mạch lạc, nhưng các câu hỏi liệt kê phạm vi nhiệm vụ vẫn cần người dùng xem xét theo chính sách câu hỏi nguyên tử. Không tự cấp nhãn chuẩn cho ba bài này.

## Vì sao sửa nhiều vẫn còn lỗi?

1. **Kiểm tra cấu trúc không kiểm tra được toàn bộ ý nghĩa.** Backend đếm đúng từ và kiểm tra quote tồn tại, nhưng không tự hiểu “picnic” và “cắm trại” khác nhau hay vai học viên bị đổi thành đồng nghiệp.
2. **Reviewer vẫn có xu hướng đồng ý với dữ kiện được đưa sẵn.** Một câu trả lời chứa nhiều thông tin có thể bị reviewer đóng gói thành một chuỗi `answerUnitsVi`; kiểm tra độ dài danh sách vì thế không đủ phát hiện câu hỏi ghép.
3. **Nhãn kỹ năng chưa đáng tin.** QUESTIONS có thể gọi câu hỏi chi tiết là reason/purpose hoặc gọi thông tin nói thẳng là suy luận; reviewer cũng có thể đồng ý.
4. **Cá nhân hóa tạo thêm xung đột ưu tiên.** Chương trình thi cần định hướng phong cách, nhưng không được thay quan hệ, vai và mục tiêu rõ trong input.
5. **GENERATOR chưa bám ngân sách từ ổn định.** Cả bốn lỗi độ dài đều là thiếu từ; có bài chỉ thiếu một từ. Gate vẫn hoạt động đúng chính sách, nhưng tỷ lệ tạo bài thành công giảm.
6. **Một hội thoại liên tục và quy tắc bằng chứng từng activity có thể lệch nhau.** case-20 cho thấy cần làm rõ semantics này trước khi chỉnh prompt hoặc validator.

Vì vậy, thêm nhiều chỉ dẫn hoặc vài ví dụ chưa đủ để chứng minh cải thiện. Cần dùng các lỗi đã lưu làm bộ hồi quy, đánh giá thay đổi trên cùng input/context, và đo riêng lỗi reviewer bỏ lọt.

## Hồ sơ và khả năng kiểm tra lại

Thư mục ngoài Git:

`C:/Users/HP/Desktop/English_dictition/data/scenario-evidence/personalized20-20261103/`

- `index.html`: xem từng bài, hội thoại/dịch, câu hỏi/đáp án/bằng chứng, lỗi gate, ghi chú agent.
- `all-twenty.json`, `case-01.json`…`case-20.json`: dữ liệu có cấu trúc; phần chưa sinh giữ nguyên thiếu.
- `manual-review.csv`: phiếu cho người dùng, để trống nhãn con người.
- `agent-review.json`: nhận xét riêng của agent, không đưa vào human gold.
- `offline-verification.json`: chạy lại chính workflow với messages/schema/options đã lưu; **40/40 phase trùng kết quả, 0 network calls**; `httpx.AsyncClient.send` bị chặn trong phép kiểm tra.
- `langfuse-audit.json`: HTTP 200, **135 observations = 95 GENERATION + 40 SPAN**, đủ 40 trace, có liên kết trace từng case/phase.
- `receipt.json`: SHA256 gói ZIP và kết quả kiểm tra CRC/hash từng thành viên.

Gói sao lưu: `C:/Users/HP/Desktop/English_dictition/data/scenario-evidence/personalized20-20261103.zip`.

Capture gốc bất biến nằm trong `data/personalized20-20261103/capture/`: input/context, prompt/messages/schema, raw response trước gate, usage, model/source, trạng thái lỗi và inventory SHA256. Kết quả phân tích bổ sung nằm riêng trong `derived/`, không thay bản lưu gốc. Scripts thử nghiệm chỉ nằm trong ignored `data/`, không thêm vào code production.

## Phạm vi kết luận và bước tiếp theo

- Đã kiểm tra: pipeline AI hiện tại trên 20 happy cases, ba chương trình, đủ sáu CEFR, các gate và khả năng tái hiện output đã lưu.
- Chưa kiểm tra: GRADE với đáp án người học, TTS, trải nghiệm toàn bộ qua API/UI/DB, độ khó nghe thực tế, độ tương đương đề thi và độ ổn định qua nhiều lượt.
- Corpus nhỏ, không có nhãn giáo viên và không cùng context với benchmark cũ: chưa chứng minh prompt hiện tại tốt hơn hay kém hơn phiên bản cũ.
- Bước hợp lý kế tiếp: người dùng chấm output đã xuất; chốt quy tắc câu hỏi nguyên tử và phạm vi bằng chứng; sau đó sửa có mục tiêu từng nhóm lỗi, giữ nguyên corpus/context/model để đo lại. Không tự sửa prompt trong lượt đánh giá này.

## Kiểm tra hồ sơ

- Replay không mạng: 40/40 trùng kết quả.
- Langfuse: đã xác minh dữ liệu nhận từ server, không chỉ bật cờ tracing.
- Gói sao lưu: kiểm CRC và SHA256 từng thành viên khi xuất.
- Không lưu API key, Langfuse key, runtime env hay cấu hình bí mật vào báo cáo/Git/gói output.
- Không triển khai production trong lượt đánh giá này.
