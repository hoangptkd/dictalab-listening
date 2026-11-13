# V4.13 / V4.14 — Kết quả kiểm thử Few-shot

Ngày: 14/11/2026, UTC+7. Người dùng đã xác nhận chạy tests và đối chiếu AI thật.

## Kết luận

**Chưa có bằng chứng V4.14 tốt hơn V4.13. Không kích hoạt V4.14.** Giữ nguyên cấu hình local V4.13; production không thay đổi.

Đã tích hợp candidate với 25 ví dụ theo từng bước, thêm version/hash/IDs để truy vết và kiểm tra tương thích bài cũ. Tests chứng minh code nối đúng, không chứng minh AI sinh nội dung tốt hơn. Phép đo thật cho thấy chấm điểm hòa, lỗi reviewer chưa hết và đầu vào dài hơn đáng kể.

## 1. Phạm vi và cách đo

- 15 ca mỗi bản: hai EXTRACT, hai pipeline sinh bài từ cùng specification đã xác nhận, hai REVIEW_QUESTIONS trên ngân hàng cố định, một QUESTIONS trên cùng transcript, tám GRADE trên targets/câu trả lời cố định.
- Tái sử dụng đầu vào và nhãn chẩn đoán đã lưu; không lấy những ví dụ few-shot mới làm đầu vào benchmark. Nhãn do agent biên soạn, chưa phải human gold.
- Cùng cấu hình: Gemini `gemini-3.5-flash-lite`, temperature 0, fallback `gemini-3.1-flash-lite`; cùng schema, validator, ngân sách output và harness. Chạy tuần tự, đảo thứ tự hai bản giữa các ca.
- EXTRACT được đo riêng; GENERATE dùng cùng specification cố định cho hai bản để không trộn tác động của kết quả EXTRACT mới.
- Giữ harness sửa có giới hạn của ứng dụng; không tự gọi lại pipeline đã trả kết quả. Các ca QUESTIONS/reviewer riêng không được thêm repair.
- Có 45 lượt gọi logic, 50 HTTP POST: 44 HTTP 200, sáu HTTP 503. Năm lỗi 503 đã fallback thành công sang 3.1; một lỗi gặp sự cố công cụ ghi nhận, nêu dưới đây.
- Chín cặp dùng cùng model thực tế và có kết quả so được, gồm một EXTRACT, một QUESTIONS bị validator từ chối ở cả hai bản, bảy GRADE. Sáu cặp còn lại bị trộn model/fallback hoặc thiếu baseline đầy đủ; không dùng để tuyên bố thắng/thua do prompt.

### Sự cố công cụ đo — giữ nguyên bằng chứng

1. Khi baseline văn phòng nhận HTTP 503 ở QUESTIONS, hook lưu phản hồi giả định JSON luôn là object; phản hồi kiểu khác gây `AttributeError`, chặn gateway trước fallback. Bài này **không được tính là lỗi prompt hoặc lỗi fallback của ứng dụng**. Không chạy lại các bước đã hoàn thành; tiếp tục chỉ các ca chưa gọi nhà cung cấp, trong thư mục `continuation`.
2. Hiển thị báo cáo experiment gặp lỗi console Unicode sau khi summary đã lưu. Đã chuyển console tiếp theo sang UTF-8; không mất raw output.
3. Validator của phép đo QUESTIONS ban đầu đọc context đã projection, thiếu bốn trường nullable. Đã bổ sung `null` chỉ khi kiểm tra offline, xác nhận dump loại null khớp tuyệt đối input đã gửi. Không gọi AI lại. Sau khắc phục công cụ đo, **cả hai output vẫn thực sự lỗi evidence**; xem mục 3.

Các file gốc không bị ghi đè. `reconciled-analysis-v2.json` là bản tổng hợp đã phân loại lại sự cố công cụ; `summary.json` gốc vẫn giữ nguyên.

## 2. Kết quả

| Hạng mục | V4.13 | V4.14 | Diễn giải |
| --- | --- | --- | --- |
| Tests code | Hành vi cũ giữ nguyên | 244 Python + 129 Java đạt | Không phải điểm chất lượng AI |
| GRADE, bảy cặp cùng model | 7/7 khớp nhãn | 7/7 khớp nhãn | Hòa; chưa có tăng chất lượng |
| GRADE, toàn bộ tám ca | 7/8 khớp nhãn lưu | 7/8 khớp nhãn lưu | Ca thứ tám khác model và nhãn còn tranh luận |
| QUESTIONS, cùng transcript/model | Bị validator từ chối | Bị validator từ chối | Cùng lỗi thiếu evidence hai người cho agreement |
| Reviewer, bộ có lỗi | Trả PASS | Trả PASS | Cả hai bỏ sót lỗi; model thực tế khác nhau |
| Reviewer, bộ đã chỉnh | Trả PASS | Trả PASS | Không thấy false rejection ở mẫu này; chưa đủ suy rộng |
| Pipeline văn phòng | Bị ngắt do hook sau 503 | Trả bài, có một repair cấu trúc | Thiếu baseline hoàn chỉnh, không xếp hạng |
| Pipeline bảo tàng | Trả bài, năm lượt AI | Trả bài, sáu lượt AI gồm repair | V4.14 fallback từ QUESTIONS; không xếp hạng nội dung theo cặp |

### Chi phí đầu vào và độ trễ quan sát

Trên **cùng bảy ca GRADE, cùng model 3.5**:

| Chỉ số | V4.13 | V4.14 |
| --- | ---: | ---: |
| Prompt tokens, tổng | 4.725 | 14.245 |
| Completion tokens, tổng | 1.674 | 1.427 |
| Thời gian gateway trung bình | 1,717 giây | 1,694 giây |

Prompt tokens tăng **3,01 lần**, không cải thiện nhãn chấm ở mẫu này. Chênh lệch thời gian 0,023 giây quá nhỏ để kết luận nhanh hơn; chưa đo chi phí tiền, cache hay phân phối độ trễ qua nhiều lần chạy.

Năm system prompt sinh bài tăng từ 28.081 lên 53.840 byte UTF-8, khoảng 91,7%; không phải mọi ví dụ đều được gửi vào mọi bước. Bộ 25 ví dụ được phân theo chín bước.

## 3. Vấn đề còn lại và bằng chứng

### P1 — Reviewer chấp nhận targets vượt phạm vi câu hỏi

Nguồn: `continuation/review-negative-v414.result.json`, trace `b90a442abfaf324ba1b1e4d698416615`, model 3.5. Reviewer trả `valid=true`, không issue, mọi cờ đều true dù:

- **a1/q1:** chỉ hỏi vì sao Lan thích truyện ngắn; câu hỏi đã cung cấp lựa chọn truyện ngắn. Target vẫn yêu cầu cả lựa chọn truyện ngắn thay vì chỉ lý do (`m1/p1`). Reviewer không phát hiện phần đã được cho trong câu hỏi bị tính lại vào yêu cầu chấm.
- **a2/q3:** hỏi lợi ích của giải pháp đọc trong 15 phút đầu. Target vẫn chứa yêu cầu nhắc thời lượng/cách đọc đã được cho (`m2/p1`), ngoài hai lợi ích thực sự cần trả lời.
- **a1/m2:** transcript là “some members **might** fall behind”; meaning cha là “which **causes** some members to fall behind”, trong khi part vẫn giữ “might”. Reviewer bỏ qua tăng mức chắc chắn và bất nhất cha/part.

Baseline fallback 3.1 cũng trả PASS trên cùng bộ lỗi. Không suy ra hai prompt bằng nhau trên cùng model từ cặp này; riêng kết quả V4.14 đã đủ chứng minh phản ví dụ trong few-shot chưa bảo đảm reviewer áp dụng đúng trong bài đầy đủ. Không lấy toàn bộ nhãn lỗi cũ làm chân lý: báo cáo tập trung các bất nhất đọc trực tiếp được trên input.

Hướng xử lý tiếp: tập trung cách reviewer đối chiếu thông tin được hỏi với từng part và mức chắc chắn trên nguồn. Đo bằng chính output đã lưu trước khi thêm ví dụ hoặc kéo dài prompt tiếp. Chưa sửa prompt lần nữa trong đợt đo này.

### P1 — QUESTIONS vẫn sinh agreement thiếu cặp bằng chứng

Nguồn: `continuation/call-18.result.json` (V4.13) và `call-19.result.json` (V4.14), cùng model 3.5, cùng transcript.

**a3/q2** gắn `speechAct=agreement` nhưng chỉ dẫn câu `Agreed.` của Lan ở s2, thiếu phần đề xuất trước đó từ người nói còn lại. Validator hiện có từ chối đúng: `agreement requires ordered evidence from both speakers`.

V4.14 đã có ví dụ agreement với cả hai người, nhưng lỗi vẫn xuất hiện. Không bỏ validator để làm tỷ lệ đạt đẹp hơn. Trên pipeline văn phòng V4.14 cũng gặp loại lỗi này và harness sửa được; trên bảo tàng phải sửa skill ngoài profile. Hai lần sửa thành công là bằng chứng cơ chế phục hồi có hoạt động, không phải bằng chứng đầu ra lần đầu đạt chuẩn.

### P2 — Chất lượng sư phạm chưa được bảo đảm bởi PASS

Bài văn phòng V4.14 có câu hỏi “Linh đã hỏi…” dễ biến thành nhớ lại nhân vật hỏi gì thay vì kiểm tra nội dung quy định. Nhãn `main-idea` đôi lúc gắn cho câu hỏi địa điểm cụ thể. Đây là nhận xét đọc thủ công trên mẫu nhỏ, không phải điểm số đã được người dùng gán nhãn.

Bài bảo tàng V4.14 có thể đọc liền mạch, nhưng dùng thêm bối cảnh gia đình và thông tin vé; V4.13 chọn hướng khác. Do workflow khác model từ QUESTIONS trở đi và nội dung không cố định, không kết luận bản nào tự nhiên hơn. Không quy mọi chi tiết hư cấu hợp lý thành hallucination.

### P2 — Một nhãn GRADE cần duyệt lại, không tự sửa để tăng điểm

Ca `heldout-unrelated-recap`: người học viết “They mentioned some books.”, target là quyết định loại sách đầu tiên cho câu lạc bộ. Nhãn lưu đòi tất cả `UNASSESSED`; cả hai model chấm m1 `PARTIAL` và các mục khác `UNASSESSED`.

Câu này có nhắc đúng chủ đề sách nhưng thiếu ý quyết định/chọn loại sách. Có thể tranh luận đây là hiểu một phần rất yếu hay chưa đủ chứng minh mục tiêu. Vì vậy báo **không khớp nhãn**, chưa gọi chắc chắn là lỗi chấm quá rộng và không thay nhãn cũ. Bảy ca rõ hơn đều giữ đúng kết quả: recap đủ/thiếu, tiếng Việt/Anh, trả lời thiếu một phần, sai và để trống.

## 4. Code đã hoàn thiện trong candidate

- 19 ví dụ mới, tái sử dụng sáu ví dụ tương thích; scoped examples không thay số lượng output bắt buộc.
- Bổ sung cả trường hợp được chấp nhận và bị từ chối ở reviewer; GRADE dùng đúng projection thật và giữ tổng hợp trạng thái bằng code.
- Pin V4.14: `66f97b88e087e528f71b6e1f7bcec1224361b2edea94623ce93e4b5c1cf1189b`.
- Example-set hash: `bbe6e79430ae52346ff009b24aea445f9e48adb6fce31004e0b5612fcbf47a88`.
- Java nhận diện đúng pin/policy, chỉ cho phép preview qua cơ chế local/loopback đã có. Không tự duyệt hoặc đổi cấu hình.
- Trace candidate có version/hash/IDs ví dụ; metadata các bản cũ giữ nguyên. `calls` nêu những ví dụ của bước thực chạy.
- Khai báo asset JSON trong package data; wheel build thành công và asset trong wheel khớp từng byte với nguồn. Docker đã copy toàn bộ thư mục app nên không cần đổi Dockerfile.
- Không đổi chính sách chấm, schema, validation, TTS, bảo mật, fallback, dataset học, ngân sách 5 lượt thường/9 lượt tối đa, hoặc thêm audit.

## 5. Checks và bằng chứng giữ lại

Code checks:

1. Python candidate/scoped/observability: **64 passed**.
2. Python assessment/parts/evidence/direct/gateway: **180 passed**.
3. Java Content/Assessment/Harness/EngineIntegration: **129 tests, 0 failure/error/skip**.
4. UTF-8 và `git diff --check` đạt. Có cảnh báo tương thích/deprecation của dependencies trên Python 3.14; các lượt kiểm tra không lỗi.
5. Build wheel bằng dependencies đang có, không cài thêm; kiểm tra có module candidate và asset JSON đúng nội dung. Kết quả tại `packaging-check.json`.

Gói bằng chứng riêng, ngoài Git:

`data/conversation-v4_14-fewshot-evaluation-20261114/`

- `manifest.json`, `frozen-inputs.json`: pins, model/config, nguồn và hash đầu vào trước khi chạy.
- `call-*.started.json`, `call-*.result.json`: messages, schema, raw response, HTTP status, usage, model thực tế, source ID, fallback và thời gian; không lưu Authorization/API key.
- `continuation/`: chỉ các ca chưa gọi khi phép đo đầu bị ngắt.
- `reconciled-analysis-v2.json`, `analyze.py`: phân loại lại lỗi công cụ và kiểm tra offline, không sinh dữ liệu mới. Bản analysis đầu cũng giữ nguyên.
- `python-regression.log`; Java log tại `data/conversation-v4_13-simplification-20261113/java-v414-final.log` và XML Surefire.

[Experiment Langfuse, phần tiếp tục](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/experiments/results?baseline=82874e6b1293faf2). Điểm `execution-contract` trên experiment gốc có hai ca lỗi context của công cụ; dùng kết quả offline đã phân loại, không lấy điểm dashboard làm chất lượng ngữ nghĩa.

## 6. Quyết định phát hành

Giữ **V4.13**. V4.14 nằm trong code dưới dạng candidate có pin riêng, để đối chiếu và duyệt ví dụ; chưa thay runtime hoặc mặc định. Chưa kiểm tra lại browser/TTS vì đợt này chỉ thay few-shot và metadata. Không có triển khai production.
