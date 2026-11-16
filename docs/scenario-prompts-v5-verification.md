# V5.0 — kiểm thử và cách so sánh local

Ngày 16/11/2026, giờ Việt Nam/Thái Lan. Phạm vi: ứng viên mới cho người dùng tự so sánh.
**Đã tạo và tích hợp đủ chín prompt. Chưa chứng minh tốt hơn V4.15; chưa chọn làm bản mặc định.**

## Mở kết quả

- [So sánh hai lượt cuối](http://localhost:18082/admin/ai-evaluation?view=compare&left=7033d69b-8a36-4e3b-bad0-e3db2068dbd4&right=6efd6b1d-fb93-4eb8-9794-5565588ed75b) — nhấn **So sánh** nếu trang mới chỉ chọn sẵn hai lượt.
- [Nội dung V5.0](http://localhost:18082/admin/ai-evaluation?view=detail&run=6efd6b1d-fb93-4eb8-9794-5565588ed75b).
- [Nội dung V4.15](http://localhost:18082/admin/ai-evaluation?view=detail&run=7033d69b-8a36-4e3b-bad0-e3db2068dbd4).
- [Toàn bộ chín prompt và nguồn phương pháp](scenario-prompts-v5.md).

Đăng nhập tài khoản quản trị local hiện có. Phiên kiểm thử dùng tài khoản chủ dự án chỉ định,
đã logout phiên riêng; không tạo tài khoản, đổi mật khẩu hay lưu credential trong báo cáo/Git.
Không xóa bài học, lịch sử đánh giá hoặc kết quả bị lỗi của các lượt trước.

## Phiên bản

- Candidate: `SCENARIO_MEANING_ASSESSMENT_V5_0`.
- Hash: `b8026ffc1dabdb09393c3b419b41750ca51ece9a242363482e3a337f4a61697b`.
- Đối chứng: V4.15, hash `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.
- Pin mặc định vẫn V4.15. V4.17 được lưu nguyên vào lịch sử; kiểm tra hash các phiên bản cũ đạt.
- Không thay model/pool/fallback hoặc nới hạn mức nhà cung cấp. Hạn mức ứng dụng vẫn tắt theo chính sách chủ dự án.
- Không thay schema, cơ chế xác thực, CSRF, quyền sở hữu, giới hạn sửa hay cơ chế kiểm chứng trích dẫn.

## Kết quả lượt cuối

| Kiểm tra | V4.15 | V5.0 |
|---|---:|---:|
| Bài được sinh hoàn chỉnh | 3/3 | 3/3 |
| Các chương trình | Giao tiếp B1, TOEIC 255, IELTS 5.5 | Cùng đầu vào và profile |
| Lượt gọi logic | 20 | 19 |

`judge=false` để chủ dự án tự đọc và so sánh; **không có điểm chất lượng từ một judge độc lập**.
Reviewer trong pipeline vẫn chạy thật. `qualityPassed=true` của ba bài V5 không chứng minh mọi câu đúng.
Không dùng 3/3 hoàn tất, JSON hợp lệ hay số lần gọi để kết luận ưu thế sư phạm hoặc hiệu năng.

Model cấu hình là Gemini 3.5 Flash Lite; fallback Gemini 3.1 Flash Lite vẫn hoạt động.
Model thực tế ở các bước V5:

| Chương trình | Model thực dùng |
| general-communication | gemini-3.1-flash-lite, gemini-3.5-flash-lite |
| toeic-listening | gemini-3.1-flash-lite, gemini-3.5-flash-lite |
| ielts-listening | gemini-3.5-flash-lite |

Hai bài V5 có chuyển sang 3.1. Đối chứng và candidate không luôn cùng model thực ở từng bước,
nên không thể quy mọi khác biệt đầu ra cho prompt. Không chạy lại để che lỗi provider hoặc tìm một lượt toàn xanh.

## GRADE và hai bước sửa

- 11/11 ca thường khớp kỳ vọng: đáp án tham chiếu Việt, sai nội dung và bỏ trống cho mỗi chương trình;
  thêm đáp án Anh và trộn Việt/Anh cho bài giao tiếp. Kỳ vọng do agent lập và đối chiếu transcript,
  chưa phải bộ nhãn chuẩn do chủ dự án duyệt.
- 0/2 ca khóa đáp án bị thêm ý thừa được từ chối đúng. Với câu hỏi mục tiêu giảm rác, khóa bị thêm
  phần vị trí thùng rác: model vẫn chấm đáp án đúng là PARTIAL. Ca bỏ trống cũng không phát hiện khóa sai.
  Đây là lỗi còn lại của GRADE; chỉ dẫn phát hiện khóa sai trong prompt chưa đủ bảo đảm model thực hiện.
  Các probe này không ghi điểm vào lịch sử học viên.
- REPAIR_TRANSLATIONS thật trả đúng một lượt được phép, khôi phục nội dung nguồn; các lượt khác giữ nguyên.
- REPAIR_QUESTIONS đã chạy trong pipeline cuối và qua kiểm tra sau sửa. Probe độc lập bổ sung chưa được tính đạt:
  bộ dựng probe dùng draft đầu tiên còn lỗi ở câu khác, nên kiểm tra toàn bài từ chối phần không thuộc phạm vi sửa.
  Bản patch probe còn có cách dịch “reduce by 30%” thành “xuống 30%” và mở rộng câu trả lời; không tính
  tuân thủ JSON/phạm vi là chất lượng ngữ nghĩa. Không nới kiểm tra hoặc mở rộng phạm vi sửa để nhận patch.

## Lỗi nội dung còn thấy khi đọc thủ công

- TOEIC a1/q1 hỏi “Tại sao…” nhưng skill là main-idea; một số câu hành động/giải pháp TOEIC và IELTS
  cũng gắn main-idea chưa thuyết phục. Reviewer đã duyệt những nhãn này.
- TOEIC a2/q1 nói “không nhận được phản hồi”, trong khi transcript có thư trả lời tự động. Cần phân biệt
  chưa nhận số liệu/phản hồi trực tiếp với hoàn toàn không có phản hồi.
- Một số answerVi còn dùng từ “slide” và câu dịch chưa thật tự nhiên. Cần người dùng đánh giá cách diễn đạt.
- Mẫu nhỏ chỉ gồm B1/standard và focus main-idea. Chưa kiểm hết các trình độ, focus, tình huống hoặc TTS.

## Các lượt trước được giữ nguyên

- Lượt đầu V5: 1/3 bài hoàn tất. Hai bài bị bộ lọc từ khóa thử nghiệm V4.17 nhận nhầm phạm vi
  (chi phí là nội dung của lý do; “liệu” trong “dữ liệu” bị nhận là mức chắc chắn).
  Khi tích hợp ban đầu đã đưa bộ lọc này vào V5; sau kiểm chứng, bỏ phần kế thừa mới đó.
  V4.17 vẫn giữ nguyên bộ lọc và hành vi cũ. V5 dùng kiểm tra cấu trúc/trích dẫn như đối chứng V4.15,
  cùng reviewer từng part và GRADE có transcript. Không bỏ kiểm tra vốn có của đối chứng.
- Lượt hai V5: 2/3 bài hoàn tất; phần sửa yêu cầu nhắc lại lời đề xuất dù câu hỏi chỉ hỏi lý do.
  Đã chỉnh tổng quát hai prompt QUESTIONS/REPAIR_QUESTIONS: trích dẫn là bằng chứng, không tự trở thành ý phải chấm.
- Không xóa các lần chạy bị lỗi khỏi Studio. Các snapshot cũ có hash/backend khác, dùng để đọc lại;
  hai liên kết ở đầu báo cáo là lượt cuối của candidate đã bàn giao.

## Kiểm tra mã và UTF-8

- Trước lượt chỉnh cuối: toàn dịch vụ Python 942 đạt, 27 bỏ qua.
- Sau chỉnh cuối: 53 kiểm tra luồng prompt, lịch sử, kiểm duyệt và vận chuyển local đạt.
- Nhóm tools/ai-eval, model-serving và ba nhóm training theo CI: 252 đạt.
- Java ScenarioAssessment, AiEvaluationStudio, ScenarioEngineIntegration đã đạt;
  kiểm tra lại pin cuối ScenarioAssessment: 17/17 đạt.
- Kiểm tra UTF-8, biên dịch cú pháp Python và diff đạt. Không sửa hoặc chuẩn hóa văn bản lịch sử.
- Có cảnh báo thư viện hiện hữu trên Python 3.14; CI dùng Python 3.12 theo workflow.

Có thể chạy lại từ root repository:

```powershell
python -m pytest services/ai-generation/tests/test_scenario_assessment_fresh.py services/ai-generation/tests/test_scenario_assessment_scope.py services/ai-generation/tests/test_scenario_assessment_alignment.py services/ai-generation/tests/test_scenario_evaluation.py services/ai-generation/tests/test_scenario_prompt_consolidation.py -q
mvn -B -q '-Dtest=ScenarioAssessmentTest,AiEvaluationStudioTest,ScenarioEngineIntegrationTest' test
```

Bằng chứng AI thật được lưu riêng trong `C:/Users/HP/.dictalab/local/` và Studio; không đưa runtime/credential vào Git.
Push và CI được báo riêng trong phản hồi bàn giao. **Không merge main, kích hoạt production hoặc deploy.**
