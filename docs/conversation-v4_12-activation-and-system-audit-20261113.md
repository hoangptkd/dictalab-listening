# V4.12 — kích hoạt local và kiểm tra prompt/hệ thống

Ngày: 13/11/2026. Phạm vi: local, tài khoản Scenario Local QA ID 14.

## 1. Trạng thái đã kích hoạt

- Mở local: **http://127.0.0.1:18082/conversation**. Core chạy profile `local`, database `dictalab_personalized_local_20261102`; QA exemption chỉ áp dụng ID 14.
- Bản đang chọn: `SCENARIO_MEANING_ASSESSMENT_V4_12`.
- Prompt hash: `77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1`.
- `ScenarioRelease` dùng approval hiện có để chọn V4.12 khi đồng thời có preview local, hash chính xác, địa chỉ loopback và không có profile prod/production. Named-user/program approvals vẫn giữ nguyên. Bài đã lưu giữ pin cũ để chấm đúng phiên bản.
- **Không đổi default toàn cục V3, không triển khai production.** Đây là kích hoạt bản thử local theo yêu cầu, không phải chứng nhận V4.12 đã đạt chất lượng.
- Primary `gemini-3.5-flash-lite`; sau lần lỗi 503 đã cấu hình fallback sẵn có sang `gemini-3.1-flash-lite` trong API/worker local. Không sửa thuật toán routing hoặc tự chạy lại job UNKNOWN.
- Core health `UP`, API liveness `ok`, worker còn chạy; không còn operation PENDING/RUNNING lúc kiểm tra cuối 14:33 UTC. Ba job UNKNOWN do provider ở lần đầu được giữ nguyên.

Thay đổi source chỉ gồm lựa chọn phiên bản local và test tương ứng. **Không sửa prompt/harness để làm mẫu kiểm tra đạt.**

## 2. Kết quả thực tế

### 2.1 Lần đầu: primary, chưa cấu hình fallback

| Ca | EXTRACT | GENERATE | Nguyên nhân |
| --- | --- | --- | --- |
| Giao tiếp B1 | Thành công | UNKNOWN | Gemini HTTP 503 ở QUESTIONS |
| TOEIC | Thành công | UNKNOWN | Gemini HTTP 503 ở QUESTIONS |
| IELTS | UNKNOWN | Chưa chạy | Gemini HTTP 503 ở EXTRACT |

Log xác minh đây là lỗi provider; không dùng các ca này để kết luận prompt kém. Đã giữ output GENERATE trước lỗi, trạng thái/trace thật; không phát lại ngầm các job này.

### 2.2 Lần mới riêng: cùng primary, có fallback

| Ca | EXTRACT | GENERATE | Model thực tế và kết luận |
| --- | --- | --- | --- |
| Giao tiếp B1 | Thành công | GENERATION_REJECTED | GENERATE 3.5; QUESTIONS gặp 503 rồi sang 3.1; repair 3.1. Còn lỗi evidence/profile và repair chưa hết lỗi |
| TOEIC | Thành công | Thành công **kèm cảnh báo chất lượng** | Cả tám call của pipeline sinh bài dùng 3.5; câu hỏi agreement đã được repair; reviewer và bản dịch vẫn có vấn đề |
| IELTS | Thành công | GENERATION_REJECTED | Các call sinh/repair dùng 3.5; thiếu evidence của người đồng ý; repair sửa vượt phạm vi |

Một trong ba ca tạo được bài. **Không coi đây là tỷ lệ chất lượng tổng thể hoặc bằng chứng V4.12 thắng V3**: mẫu nhỏ, có repair và ca giao tiếp dùng model hỗn hợp.

### 2.3 Luồng học trên bài TOEIC vừa tạo

Đã chạy bằng API thật có đăng nhập/CSRF, không dùng mock:

| Kiểm tra | Kết quả |
| --- | --- |
| Pin V4.12 của draft/payload | Đúng hash |
| Gửi trùng yêu cầu tạo với cùng idempotency key | Cùng operation, không tạo job thứ hai |
| Gửi version cũ | HTTP 409 |
| Đáp án/transcript trong public session trước hoàn thành | Không bị lộ |
| TTS một segment | WAV mono, 16 bit, 24 kHz, 5,84 giây |
| Activity 1, đáp án tiếng Anh đầy đủ | CAPTURED |
| Activity 2, bỏ trống toàn bộ | MISSED, không được cộng hiểu nhầm |
| Activity 3, chỉ nói chung về chủ đề | UNASSESSED |
| Activity 3, trả lời đúng bằng tiếng Anh/Việt | CAPTURED |
| Hoàn thành ba activity, mở lại session | COMPLETED, dữ liệu nhất quán |
| Payload của 213 operation lịch sử | Hash không đổi |
| Phiên đăng nhập của runner | Đã logout |

Activity 2 vẫn hiển thị **0 ý hiểu / 3 ý bỏ lỡ** khi session hoàn thành. COMPLETED đang có nghĩa đã kết thúc lượt làm, không có nghĩa đạt mức hiểu bài. Nếu muốn bắt buộc mastery trước khi luyện tiếp, cần quyết định sản phẩm riêng.

Runner đặt tên activity 2 là `partial-blank`, nhưng thực tế đã gửi blank cho toàn bộ câu. Không tính lần đó là test PARTIAL. Đã bổ sung ba probe chấm riêng trên chính meaning/câu hỏi của bài TOEIC đã lưu, không sinh lại bài:

| Câu trả lời | Mong đợi | Thực tế |
| --- | --- | --- |
| `The competitor is a major factor.` | PARTIAL | PARTIAL |
| `There are no competitors, and service speed cannot affect repeat visits.` | INCORRECT | INCORRECT |
| `The competitor is a major factor. Service speed might also affect repeat visits.` | CAPTURED | CAPTURED |

Cả ba probe dùng Gemini 3.5. Kết quả 3/3 chỉ áp dụng cho ba đáp án trên một meaning; chưa chứng minh chất lượng chấm mọi câu.

### 2.4 Chạy lại năm lỗi đã gán nhãn F1–F5

Cùng frozen input, V4.12 hash trên, Gemini 3.5, temperature 0; không retry/fallback. Bốn POST mới: ba stage negative HTTP 200, stage đầu positive HTTP 503 rồi dừng.

| Lỗi đã gán nhãn | Kết quả mới |
| --- | --- |
| F1: câu hỏi lý do nhưng target chấm thêm sở thích | Bắt đúng |
| F2: câu hỏi lợi ích nhưng target chấm thêm hành động/thời lượng | Bắt đúng |
| F3: parent đổi `might` thành khẳng định | Bỏ sót |
| F4: requiredParts thiếu ý đồng ý có trong parent | Bỏ sót |
| F5: một part chứa hai lợi ích độc lập | Bỏ sót |

**Vẫn 2/5 lỗi trên một mẫu chẩn đoán; không có tiến bộ mới so với [lần retest trước](conversation-v4_12-quality-retest-20261113.md).** Không diễn giải thành accuracy 40%. Positive lần mới không có kết quả do 503; lỗi từ chối nhầm của positive trong báo cáo trước vẫn là bằng chứng lịch sử, không phải kết quả mới của lần này.

## 3. Các lỗi cần xử lý tiếp

### P1 — Prompt QUESTIONS sinh evidence sai activity và skill ngoài profile

Ca giao tiếp a1/m1 trỏ s3 với quote `Looks like this is my floor now`, nhưng câu này thuộc **a3/s3**, không phải a1/s3. a3/m3 và q3 dùng `speaker-attitude` ngoài profile B1 được cấu hình. Đây là lỗi AI tạo câu hỏi/meaning; gate chặn có căn cứ.

Repair xử lý một số lỗi nhưng để nguyên a3/q1 đang bị chặn. Cần cho prompt repair thấy đúng target, lỗi và evidence tại activity; kiểm lại output repair bằng cùng validator. Không bỏ kiểm tra grounding/profile để nhận bài.

### P1 — Reviewer bỏ sót mức chắc chắn và quan hệ parent/parts

Không chỉ xảy ra trên frozen fixture. Bài TOEIC mới có:

- Parent a1/m3: `The new competitor and service speed are affecting repeat visits.`
- Nguồn: `…our service speed might also be affecting repeat visits.`
- Parts giữ `might`, nhưng parent mất mức chưa chắc chắn; reviewer vẫn cho `meaningSupported=true`.
- Parent a2/m3 chỉ nói hai đề xuất đòi hỏi đầu tư thời gian/tài nguyên khác nhau. Part p1 `Both proposals have merit.` không nằm trong parent, nhưng audit vẫn coi được parent hỗ trợ.

Cần kiểm chứng độc lập **source → parent**, **source → part**, **parent → part** và độ bao phủ của parts, thay vì suy ra toàn bộ đạt chỉ vì quote đúng hoặc từng part nghe hợp lý. Dùng lại dữ liệu đã lưu làm regression; chưa cần sinh thêm corpus.

### P1 — Reviewer nhầm nhiều part riêng thành một part gộp

TOEIC a1/m3, a2/m3, a3/m3 đều bị ghi `Gộp ý độc lập: p1,p2` dù p1/p2 đã là hai part riêng. Đây là cùng dạng diagnostic sai từng thấy trên positive cũ. a2/m3 đồng thời có lỗi parent/part thật ở trên; không được suy rằng toàn bộ item đúng chỉ vì nhãn “gộp” sai.

Cần làm rõ diagnostic tìm **một part chứa nhiều ý**, kiểm chứng cả ví dụ gộp thật và đã tách đúng. Không xóa distinctMeaning để che false rejection.

### P1 — IELTS repair sửa vượt phạm vi

a3/q3 ghi `speechAct=agreement`, chỉ dẫn s1/s3 của Linh, bỏ s2 của Robin: `That sounds like a helpful solution…`. Gate yêu cầu proposal và acceptance có thứ tự từ hai người nên chặn đúng.

Repair chỉ được phép sửa **a3/q3**, nhưng AI trả thêm **a3/m3**. `merge_patch` từ chối `patch targets mismatch` tại `scenario_assessment_harness.py:89`.

Cần sửa prompt/đầu vào repair để giữ đúng tập target. Giữ guard chống sửa ngoài phạm vi; không mở rộng target chỉ để patch qua.

### P1 — Validator evidence đang quá chặt về chuỗi ký tự

`scenario_assessment.py:457` yêu cầu question chứa nguyên quote của mọi evidence trong meaning được tham chiếu. Ca giao tiếp a3/q1:

- Parent: `Robin noticed Linh's plants earlier.`
- Quote parent: `I noticed the lovely green plants on your balcony when I was walking up to my door earlier.`
- Hỏi: `Robin đã thấy gì trước đó?`
- Đáp án: `Anh ấy thấy những cái cây trên ban công của Linh.`
- Quote câu hỏi: `I noticed the lovely green plants on your balcony`.

Quote ngắn có thật và đủ hỗ trợ câu trả lời, nhưng bị chặn bởi `question_omits_referenced_meaning_evidence`. Cần thiết kế kiểm tra hỗ trợ theo phần ý được hỏi và quote cùng nguồn; không đồng nhất “quote ngắn hơn” với “thiếu bằng chứng”. Đây khác lỗi quote nhầm activity ở trên. Chưa thay validator trong task này.

### P2 — Translation repair bị chặn bởi một lỗi tổng quát trùng lỗi có vị trí

TOEIC a2/s1 có typo `khách1 hàng thân thiết`. REVIEW xác định đúng segment, đồng thời đánh fail rubric TRANSLATIONS toàn cục. Harness thấy một issue không repairable nên bỏ toàn bộ sửa dịch: `attempted=false`, `reason=unlocalized_issue` (`scenario_assessment_harness.py:233`).

Cần phân biệt rubric tổng hợp của lỗi đã định vị với lỗi mới chưa định vị. Cho sửa đúng segment khi đủ căn cứ, sau đó kiểm lại toàn bộ bản dịch. Không mặc định bỏ qua mọi lỗi global.

### P2 — Bài tạo thành công chưa đồng nghĩa qua quality review

TOEIC lưu `qualityPassed=false`, questionsStatus/reviewStatus đều FAIL. Đây là **chính sách advisory hiện hữu theo yêu cầu trước**, không phải gate mới bị bypass: cảnh báo ngữ nghĩa không tự loại bài; schema, nguồn quote và phạm vi patch vẫn chặn.

Không trình bày bài này là “nội dung đạt chuẩn”. Nên quyết định cách hiển thị cảnh báo/chất lượng cho người học trước khi mở rộng. Không tự khôi phục chính sách loại bài mà người dùng đã bỏ.

### P2 — Provider/fallback và log chẩn đoán

- Lần đầu runtime thiếu danh sách fallback dù code đã hỗ trợ. Đã cấu hình fallback local và xác minh thật ở ca giao tiếp. Fallback giúp vượt 503, không đảm bảo nội dung đạt; không tự retry job UNKNOWN để tránh phí trùng.
- Frozen positive vẫn gặp 503 vì phép đo cố ý giữ model cố định, không fallback. Đây là giới hạn bằng chứng, không phải negative verdict về nội dung.
- `redaction.py:42` nhận chuỗi opaque 32+ ký tự là secret nên che cả issue code dài và hash không bí mật. Chẩn đoán vẫn khôi phục được offline từ raw output/payload riêng. Nên cho phép có kiểm soát các mã lỗi/hash định trước, giữ nguyên bảo vệ credential.

## 4. Thứ tự đề nghị để bạn xử lý

1. Sửa reviewer source/parent/parts và diagnostic gộp ý; chạy lại frozen F1–F5 cùng positive đã lưu.
2. Sửa QUESTIONS/repair: đúng activity/profile, đủ người đề xuất/đồng ý, không sửa vượt target.
3. Điều chỉnh gate quote với ca ngắn hợp lệ và ca quote nhầm activity; giữ grounding fail closed.
4. Sửa lựa chọn translation repair khi rubric tổng hợp cùng lỗi đã định vị.
5. Làm rõ cảnh báo quality, completion/mastery; cải thiện log diagnostic và kiểm tra fallback trong launcher local chuẩn.

Đây là danh sách đề nghị, **chưa triển khai các sửa đổi trên**. Không có bằng chứng cho thấy phải viết lại toàn bộ prompt hoặc thêm abstraction mới.

## 5. Kiểm thử code và hồ sơ tái kiểm tra

- **125 Java tests đạt**: ScenarioContentTest 13, ScenarioAssessmentTest 12, ScenarioHarnessTest 8, ScenarioEngineIntegrationTest 92.
- **105 Python tests đạt**: direct/evidence/audit modules.
- Maven package thành công; runtime chạy JAR vừa build; pin trong draft/job mới đã được xác minh.
- Source prompt/validator/harness hashes trước/sau không đổi. UTF-8 và `git diff --check` được kiểm tra trước bàn giao.

Evidence riêng, không commit dữ liệu/runtime/credential:

- `data/conversation-v412-activation-20261113/`: runtime receipt, API run02/run03 tách riêng, full raw request/result, `analysis.json`, grading probes, Langfuse readback, `final-verification.json`, SHA-256 manifest.
- `data/conversation-meaning-assessment-20261106/active-v4_12-20261113/`: frozen input, started/result từng stage, summary và manifest.
- `data/conversation-meaning-assessment-20261106/v412-activation-{java,python,build}.log`: checks/build.
- Giữ riêng các lỗi **công cụ kiểm thử** trước khi gọi AI: runner đọc nhầm preference response và probe ban đầu chưa bật scenario. Không tính chúng vào lỗi sản phẩm/chất lượng hoặc số call provider.
- Không ghi đè kết quả lỗi 503. Manifest loại trừ logs/SQLite đang thay đổi; không phải backup toàn database.

Đã đọc lại Langfuse sau cùng: 116 observations, 35 generation observations trong cửa sổ lần chạy; số này gồm các trace công cụ fail trước provider, không diễn giải thành 35 HTTP POST. Đã đối chiếu riêng các trace sau:

| Bằng chứng | Trace |
| --- | --- |
| Giao tiếp, source/repair/gate | [4c64484d…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/4c64484d3a1672633065c96ec38d26d6) |
| TOEIC, pipeline tạo có cảnh báo | [188e6cf0…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/188e6cf05bd8b830eff29889906100f9) |
| IELTS, patch ngoài target | [55d93db1…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/55d93db1df1728e117560b35b0ecb411) |
| Frozen negative mới, 2/5 | [e9ad3155…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/e9ad3155b699911593bd7a262f4f87a9) |
| Frozen positive mới, 503 | [c7f7bdad…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/c7f7bdad76c1c9076cc7b01d8883384d) |
| Grade PARTIAL / INCORRECT / CAPTURED | [partial](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/f3061da9e9705a27bc2feb4b0b154c0f), [wrong](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/2c6cf8ff18a594e30b336fa524843b40), [complete](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/01a5452b83d8755de19dd23b160c5c2e) |

## 6. Giới hạn kết luận

Đã kiểm tra luồng API thật, không phải audit browser/mobile/accessibility hay nghe đánh giá giọng TTS bằng tai. Chỉ kiểm tra một segment audio, chưa chứng nhận nghe toàn activity liền mạch/multi-voice. Chưa có so sánh full-generation V3/V4.12 trên corpus lớn cùng model. Test code xanh chứng minh tính tương thích trong phạm vi test; **không chứng minh nội dung AI đã tốt**.

Kết luận: **V4.12 đang hoạt động trên local để bạn kiểm tra, chấm điểm đã đúng các probe, nhưng reviewer và cơ chế repair còn lỗi cần xử lý trước khi mở rộng.**

## 7. Khôi phục trang quản trị Gemini sau kích hoạt

Sau khi người dùng mở `/admin/gemini`, trang báo thiếu cấu hình quản trị. Nguyên nhân là launcher preview đã đọc bộ `data/microservices/core.env` và `ai.env` chạy inference, bỏ sót cấu hình quản trị hiện hữu ở `data/microservices/gemini-admin-local/`. Đây là thiếu sót cấu hình lúc khởi động, không phải mất chức năng hoặc lỗi prompt V4.12.

Core đã có `text-transport=python` nhưng thiếu `DICTALAB_AI_PROVIDER_ADMIN_TOKEN`, nên `GeminiAdminController.exchange` từ chối trước khi gọi Python. API cũng thiếu `AI_PROVIDER_ADMIN_TOKEN`, `AI_PROVIDER_ADMIN_DATABASE_URL` và telemetry DSN.

Đã nạp lại đúng token/kết nối được bảo vệ hiện có, kiểm tra cùng database registry; nối telemetry cho API/worker. Worker không nhận credential quản trị. Sau khi xác minh không có job đang chạy, đã restart đúng ba process của preview, giữ nguyên V4.12, fallback, jobs ledger, database và cổng. Không thay API key, quyền người dùng, quota hoặc registry.

Kết quả lúc 14:53 UTC:

- Python management snapshot HTTP 200, đọc được **5 nguồn Gemini**, quotaAvailable/usageEnabled đều true.
- Hash cả năm bảng pools/accounts/labels/management/audit không đổi trước/sau.
- Core/API thiếu đăng nhập/token vẫn HTTP 401; QA ID 14 là USER, vẫn HTTP 403 ở API admin. Không nâng quyền hoặc dùng tài khoản cá nhân để test.
- Catalog của QA vẫn LOCAL_PREVIEW; pin V4.12 và fallback không đổi; bài TOEIC đã lưu vẫn COMPLETED. Phiên test riêng đã logout.
- Ba `GeminiAdminControllerTest` đạt. Chưa xác nhận render bằng phiên admin trong browser; đã xác minh kết nối backend và các điều kiện cấu hình.

Evidence riêng: `data/gemini-admin-v412-20261113/{runtime-before,restoration,verification}.json` cùng script phục hồi/kiểm tra không chứa credential. Receipt `runtime.json` được cập nhật PID mới; receipt/hash ban đầu trong mục 5 là lịch sử trước lần restart này. Bộ cấu hình quản trị được bảo vệ đã có sẵn và không bị sửa.

**Khi khởi động lại preview, phải nạp cả cấu hình quản trị trên**, ghép token vào Core/API, DSN manager chỉ vào API, DSN telemetry vào API/worker; không dùng nguyên launcher evidence cũ như cấu hình hoàn chỉnh. Không cấp manager DSN hoặc provider key cho Core/worker. Thống kê đã bật cho lượt mới; các lượt chạy trước thiếu telemetry không được tự bịa hoặc backfill từ token ước tính. Langfuse của lần đánh giá vẫn được giữ riêng.
