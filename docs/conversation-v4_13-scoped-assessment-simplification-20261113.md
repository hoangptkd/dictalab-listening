# Conversation AI V4.13 — đánh giá đúng phạm vi câu hỏi

Ngày kiểm chứng: 2026-11-13. Phạm vi: luồng sinh bài mới trên local; không triển khai production.

## 1. Hợp đồng đã sửa

| Bước | Hành vi V4.13 |
| --- | --- |
| QUESTIONS | Câu hỏi, đáp án tham chiếu, meaning và requiredParts phải cùng phạm vi. Không chấm thông tin đã cho trong câu hỏi hoặc chi tiết ngoài yêu cầu. Giữ dữ kiện, điều kiện, mức chắc chắn, vai nhân vật và bằng chứng nguyên văn cùng activity. |
| REVIEW_QUESTIONS | Đọc trực tiếp hội thoại và bản nháp câu hỏi/đáp án/targets. Kiểm tra tính đúng, đủ, rõ ràng, phạm vi, sự phủ đủ của parts và phân biệt đề xuất với đồng ý. Đáp án sai nguồn đặt `answerSupported=false`; yêu cầu thừa/thiếu/đã cho đặt `questionMatchesFact=false`. |
| GRADE | Chấp nhận tiếng Việt, tiếng Anh và kết hợp. Chỉ đánh giá các ý được hỏi; không bắt trả lời lý do khi chỉ hỏi địa điểm. Bằng chứng phải là trích dẫn nguyên văn từ câu trả lời người học. |

Code tổng hợp trạng thái parts thành meaning giữ nguyên. Đúng địa điểm nhưng sai giờ trong câu hỏi hỏi cả hai được tổng hợp thành `PARTIAL`. Không thêm tầng đánh giá, cấu hình bật/tắt hoặc dịch vụ mới.

Projection của review dùng một inventory `content`, chứa hội thoại và câu hỏi bản nháp để giữ `speechAct`/`factEn`; các trường này bị lược ở bản nội dung công khai. Projection GRADE hiện có vẫn chỉ nhận targets, câu hỏi liên quan và câu trả lời người học, không nhận đáp án tham chiếu hay toàn bộ transcript.

## 2. Hai audit đã bỏ ở đâu?

Luồng V4.13:

`GENERATE → QUESTIONS → REVIEW_QUESTIONS → TRANSLATE → REVIEW`

- Không gọi `AUDIT_MEANINGS` hoặc `AUDIT_QUESTIONS`.
- Manifest V4.13 không chứa audit prompts, output schemas, projections, diagnostics hoặc quy tắc chuyển diagnostics thành cờ. Dùng schema `QuestionReview` hiện có.
- V4.13 không bật `AUDIT_PROTOCOL`, `EVIDENCE_AUDIT` hoặc `DIRECT_PART_AUDIT`; không tạo audit artifacts.
- Giữ mã audit và prompt lịch sử vì các release đã đóng băng còn dùng chúng khi đọc manifest, kiểm tra hash, replay và kiểm thử. Không sửa hash cũ hoặc xóa benchmark đã lưu. Đây không còn là bước chạy của bài V4.13 mới.
- Giữ validation cấu trúc, ID, bằng chứng, học/chấm/hoàn thành, bảo mật, TTS, tracing và fallback.

Chính sách review vẫn là **advisory** như trước: lỗi cấu trúc/bằng chứng vẫn bị validation chặn; kết luận ngữ nghĩa không đạt của reviewer được lưu, có thể kích hoạt sửa có giới hạn, và không tự biến thành lệnh từ chối toàn bộ bài. `qualityPassed=false` cùng các cờ lỗi vẫn hiện trong kết quả. GRADE không sửa đáp án tham chiếu sai thay cho reviewer.

## 3. Số lượt AI và ngân sách

| Phạm vi | V4.12 | V4.13 | Thay đổi |
| --- | ---: | ---: | ---: |
| Sinh bài, không cần sửa/retry | 7 | 5 | −2 lượt, 28,6% |
| Sinh bài, ngân sách tối đa gồm sửa/retry của harness | 11 | 9 | −2 lượt |
| EXTRACT rồi sinh bài, không sửa/retry | 8 | 6 | −2 lượt |
| Một lượt GRADE | 1 | 1 | Giữ nguyên |

Đây là số lời gọi logic của workflow; số HTTP request thực tế có thể khác khi provider retry/fallback. Không suy ra mức tiết kiệm tiền hoặc độ trễ thực tế từ bảng này.

V4.13 dùng lại `scenario-meaning-assessment.v2` và `scenario-meaning-harness.v1`, tối đa 4 lượt bổ sung. Backend kiểm tra đúng chuỗi 5 bước và từ chối chuỗi có hai audit khi trả kết quả V4.13.

## 4. Kiểm chứng

| Kiểm tra | Kết quả |
| --- | --- |
| Python: toàn bộ `test_scenario_assessment*.py`, model gateway và Gemini fallback | 260 passed |
| Chạy lại riêng hợp đồng scoped sau lần sửa projection cuối | 11 passed |
| Java: ScenarioContentTest / ScenarioAssessmentTest / ScenarioHarnessTest / ScenarioEngineIntegrationTest | 13 + 12 + 9 + 93 = 127 passed; không failure/error/skipped |
| Build JAR | Đạt sau khi dừng đúng Core local đang giữ file JAR; lần build đầu lỗi khóa file được giữ trong log |
| UTF-8 và diff whitespace | Đạt |

Ca tập trung gồm:

1. Hỏi giờ và nơi: địa điểm đúng, giờ sai → `PARTIAL` qua code tổng hợp hiện có.
2. Hỏi Lan chọn nơi nào: chỉ chấm nơi; tiếng Việt, tiếng Anh và kết hợp đều được phép.
3. Lý do đã nằm trong câu hỏi không thành target; không thể dùng chữ từ câu hỏi làm bằng chứng người học.
4. Đáp án tham chiếu trái transcript được đưa cho review, cờ sai và `qualityPassed=false` được giữ; không gọi GRADE trong sinh bài.
5. Review bị sai format và review sau patch vẫn dùng projection trực tiếp, không tạo audit; kiểm đếm đúng lượt bổ sung.
6. Hash và GRADE bài V3/V4.12 vẫn đọc được; integration kiểm tra V4.13 qua tạo bài, checks, hoàn thành và chấm bằng pin đã lưu.

Tests hợp đồng dùng đầu ra AI giả lập. Chúng chứng minh luồng, dữ liệu và tổng hợp trạng thái hoạt động đúng; **không chứng minh model thật đã phát hiện tốt hơn các lỗi ngữ nghĩa**. Chưa chạy benchmark AI thật hoặc đo token/chi phí/độ trễ. Python 3.14 có cảnh báo deprecation asyncio từ thư viện kiểm thử, không có test lỗi.

## 5. Trạng thái local

- URL: <http://127.0.0.1:18082/conversation>.
- Kích hoạt V4.13 bằng cơ chế approved hash local preview hiện có; production/global default vẫn V3, chưa thay release production.
- Version: `SCENARIO_MEANING_ASSESSMENT_V4_13`.
- Prompt hash: `b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087`.
- Core health `UP`; Python API sống; kết nối quản lý Gemini nội bộ HTTP 200.
- Primary Gemini 3.5 Flash Lite; fallback `gemini-3.1-flash-lite` giữ nguyên.
- Profile local và QA user 14 giữ nguyên. Kiểm tra login → catalog LOCAL_PREVIEW → đọc bài TOEIC cũ COMPLETED → logout đạt.
- QA không có quyền quản trị: HTTP 403. Quản trị chưa đăng nhập/thiếu token: HTTP 401. Worker không mang credential quản trị.
- 229 payload operation lịch sử của QA giữ nguyên hash sau restart. Không gọi sinh nội dung provider trong quá trình xác minh local.

Bằng chứng runtime và logs nằm ngoài Git tại `data/conversation-v4_13-simplification-20261113/`: `activation.json`, `verification.json`, `python.log`, `scoped-final.log`, `java.log`, `integration-final.log`, `build.log`, `build-after-stop.log`. Không đưa credential hoặc dữ liệu runtime vào commit.

## 6. Giới hạn còn lại

Một reviewer vẫn có thể đánh giá nhầm đáp án hoặc phạm vi. Việc gom kiểm tra không loại bỏ rủi ro này. Cần đối chiếu AI thật với cùng input/model/cấu hình và lưu nguyên output trước khi khẳng định chất lượng tăng. Không mở lại benchmark hoặc tạo dữ liệu chỉ để có thêm con số trong lần sửa hợp đồng này.
