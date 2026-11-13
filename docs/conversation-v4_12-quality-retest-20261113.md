# Kiểm tra lại chất lượng V4.12

Ngày: 13/11/2026. **Có cải thiện một phần so với V4.11, nhưng chưa đạt điều kiện kích hoạt.**

## 1. Phạm vi và phương pháp

- Candidate: `SCENARIO_MEANING_ASSESSMENT_V4_12`.
- Prompt hash: `77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1`.
- Cùng model `gemini-3.5-flash-lite`, temperature 0, cùng hai map đã đóng băng của V4.11: một map chứa năm lỗi F1–F5 và một map đã sửa đúng. Mỗi map có ba activity, chín meanings và chín questions.
- Không đổi prompt, model, nhãn, validator hoặc dữ liệu để lấy kết quả đạt. Không fallback; giữ nguyên input và source hashes trước/sau phép đo.
- Chỉ kiểm tra AUDIT_MEANINGS → AUDIT_QUESTIONS → REVIEW_QUESTIONS trên hội thoại đã lưu. Không sinh lại dataset hoặc kiểm tra GENERATE/GRADE/audio/UI trong lần này.

Lần đầu có ba POST: hai audit negative HTTP 200, final review HTTP 503. Sau đó chạy experiment tiếp nối riêng, tối đa ba POST còn lại: gọi lại final review negative một lần sau HTTP 503 xác định, rồi question audit và final review positive. Cả ba đều HTTP 200.

**Positive tái dùng meaning ledger đã lưu từ V4.11**, sau khi xác minh cùng prompt AUDIT_MEANINGS, projected input, model và receipt hợp lệ. Mục đích là kiểm tra trực tiếp lỗi từ chối nhầm khi ledger gộp các ý. Vì vậy đây là kiểm chứng chẩn đoán có dùng ledger lưu sẵn, không phải hai pipeline đều chạy mới đủ ba stage. Negative dùng hai audit mới của lần này.

## 2. Kết quả so với V4.11

| Trường hợp | V4.11 | V4.12 lần này | Kết luận |
| --- | --- | --- | --- |
| F1: a1/q1 hỏi lý do nhưng target chấm thêm sở thích đã cho | NOT_REQUESTED nhưng COMPLETE với quote rỗng; lỗi hợp đồng, chưa tới final review | NOT_REQUESTED / INCOMPLETE, quote rỗng; chuẩn hóa `questionMatchesFact=false` | **Đã cải thiện và bắt đúng lỗi** |
| F2: a2/q3 hỏi lợi ích nhưng target chấm thêm hành động/thời lượng đã cho | Cùng lỗi hợp đồng như F1 | NOT_REQUESTED / INCOMPLETE; chuẩn hóa `questionMatchesFact=false` | **Đã cải thiện và bắt đúng lỗi** |
| F3: a1/m2 đổi khả năng “might fall behind” thành khẳng định chắc chắn | Chưa đo được final review | Vẫn `meaningSupported=true` | **Bỏ sót** |
| F4: a3/m2 parent có đồng ý nhưng requiredParts thiếu ý đồng ý | Chưa đo được final review | `uncoveredMeaningEn=[]`, `groundingSupported=true` | **Bỏ sót** |
| F5: a2/m2 gộp hai lợi ích độc lập vào một part | Chưa đo được final review | `bundledPartIds=[]`, `distinctMeaning=true` | **Bỏ sót** |
| Positive đã sửa đúng | Loại nhầm a2/m2 và a2/m3 do dùng chung claim rộng | a2/m3 không còn bị loại; a2/m2 vẫn bị loại nhầm do diagnostic gộp ý | **Vẫn chưa chấp nhận được bài đúng** |

Trên map lỗi, kết quả chuẩn hóa bắt đúng **2/5 lỗi đã gán nhãn**. Đây là số lỗi trên một mẫu chẩn đoán, không phải độ chính xác 40% của sản phẩm. Trên map đúng, `valid=false`, `issues=[]`; cờ sai duy nhất là `a2/m2.distinctMeaning=false`.

Không so sánh “V4.11 bắt 0/5, V4.12 bắt 2/5”: V4.11 dừng trước final review nên F3–F5 khi đó chưa có phép đo. Cải thiện có bằng chứng ở F1/F2 và việc bỏ cơ chế đếm claim rộng; độ tin cậy ngữ nghĩa tổng thể vẫn chưa đạt.

## 3. Bằng chứng các lỗi còn lại

### F3 — nhận đúng quote nhưng bỏ qua khác biệt mức chắc chắn

Nguồn: `some members might fall behind`.
Parent lỗi: `Novels take too long to read, which causes some members to fall behind.`
Required part vẫn giữ `might`, nên kiểm tra part có thể đạt, nhưng parent đã làm mất mức chưa chắc chắn.
Reviewer trích đúng nguồn, vẫn trả `certaintyPreserved=true` và `meaningSupported=true`; lý do nói parent phản ánh chính xác nguồn. Quote đúng không đảm bảo so sánh ý nghĩa đúng.

### F4 — kiểm tra từng part chưa chứng minh đủ toàn bộ parent

Parent có ba ý: Lan đồng ý, sẽ đặt phòng thứ Ba tới, và dự kiến thu ý kiến cuối buổi. Required parts chỉ có đặt phòng và thu ý kiến.
Reviewer xác nhận hai part được nguồn hỗ trợ nhưng trả `uncoveredMeaningEn=[]`; không chỉ ra ý đồng ý bị thiếu. Cần phân biệt “mọi part đều đúng” với “mọi ý của parent đều đã được biểu diễn”.

### F5 và positive — diagnostic gộp ý phân biệt ngược

Negative có một part: `This helps less confident members and makes story discussion easy.` Reviewer không báo gộp ý.

Positive đã tách đúng:

- p2: `This would help less confident members.`
- p3: `This would make discussing the story easier.`

Reviewer xác nhận từng part đều được nguồn/parent hỗ trợ, trích bằng chứng riêng, không có duplicate pair. Tuy nhiên lại trả `bundledPartIds=["p2","p3"]`. Backend hạ `distinctMeaning` theo diagnostic này. Đây là lỗi của verdict AI; không phải hai part thực sự bị gộp hoặc backend còn đếm số lần trỏ vào claim rộng.

Điểm cần kiểm chứng tiếp là ý nghĩa của diagnostic: phát hiện **một part chứa nhiều ý**, không coi **nhiều part riêng** là gộp. Đồng thời vẫn phải so sánh parent với nguồn và kiểm tra phần parent bị thiếu. Không bỏ các cờ này để mẫu qua kiểm tra. Lần này chỉ đo và báo cáo, chưa sửa prompt tiếp.

## 4. Chi phí, độ trễ và tracing

| Chỉ số | Kết quả |
| --- | --- |
| POST thực tế | **6**: 5 HTTP 200, 1 HTTP 503 |
| Input tokens của năm call thành công | 18.097 |
| Output tokens của năm call thành công | 16.639 |
| Tổng tokens được báo | **34.736** |
| Usage của call 503 | Không được báo; không suy là miễn phí |
| Negative: ba stage thành công | 3,803 + 7,404 + 14,226 = 25,433 giây; không gồm 503 và khoảng chờ giữa experiment |
| Positive: hai stage mới | 6,458 + 13,052 = 19,510 giây; không gồm meaning audit lưu sẵn |

Không cộng usage của meaning audit V4.11 vào chi phí lần này; chưa xác minh tiền thanh toán. Không dùng thời gian trên để khẳng định nhanh/chậm hơn toàn bộ pipeline V4.11 vì có retry và cache ledger.

Đã đọc lại Langfuse, xác nhận đủ sáu generation observations trong ba trace:

- [Lần đầu: hai audit thành công và review 503](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/ee55ad7c6d96d5f1414ad6ed86eb2a34).
- [Negative: final review tiếp nối](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/3d85582b3587d2dd9b4ff86cbb207ceb).
- [Positive: question audit và final review](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/3862672254dd4e75c54004b0c6cf97a1).

Gate score 0 của experiment tiếp nối nghĩa là cả hai map chưa đạt điều kiện nghiệm thu; không phải độ chính xác ngữ nghĩa 0%.

## 5. Hồ sơ và trạng thái bàn giao

Evidence riêng, không đưa lên Git:
`data/conversation-meaning-assessment-20261106/direct-parts-v4_12-20261113/`.

- `evaluation-started.json`, `completion-started.json`: model/hash, giới hạn calls và provenance ledger tái dùng.
- `frozen-inputs.json`: input nguyên gốc; không sinh lại bài.
- `*-*.started.json`, `*-*.result.json`: prompt/schema/projected input/raw HTTP/raw verdict/usage/duration từng stage.
- `summary.json`, `completion-summary.json`, các kết quả negative/positive: giữ nguyên từng experiment, không ghi đè lần 503.
- `analysis.json`, `analyze.py`: tổng hợp offline, kiểm tra lại cờ, usage, hash nguồn/input, ledger cache và traces; không gọi AI.
- `trace-verification.json`, `*-langfuse-observations.json`: bằng chứng đọc lại trace.
- `sha256-manifest.json`: hash các tệp evidence.

Kiểm thử code V4.12 trước đó: 368 Python và 124 Java đạt, xem [báo cáo sửa V4.12](conversation-v4_12-direct-audit-fix-20261112.md). Không chạy lại suite không đổi để thay cho kiểm chứng AI. Lần này kiểm tra kết quả lưu, SHA-256, UTF-8 và diff; không sửa source.

**Giữ default V3, chưa kích hoạt V4.12, chưa deploy.** Cần sửa/kiểm chứng reviewer trên F3–F5 và positive trước khi mở rộng sang sinh bài, chấm điểm và full flow. Kết quả chỉ giới hạn ở một hội thoại đã lưu; chưa đủ bằng chứng tuyên bố V4.12 tốt hơn V3 về chất lượng nội dung tổng thể.
