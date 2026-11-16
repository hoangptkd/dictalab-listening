# V5.1: phục hồi bài và bảo vệ việc chấm

Ngày 16/11/2026. Đã triển khai và mở bản thử local. **Đường sửa hoạt động, nhưng bộ phát hiện ngữ nghĩa vẫn bỏ sót lỗi; chưa đạt mục tiêu sửa đúng toàn bộ tập lỗi. Không dùng tỷ lệ hoàn tất để tuyên bố nội dung đúng hoặc sẵn sàng production.**

## Thay đổi

- Dùng các bước GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW, REPAIR_QUESTIONS, REPAIR_TRANSLATIONS và GRADE hiện có. REVIEW kiểm tra nguồn trước khi sinh các phần phụ thuộc. Không thêm RAG, few-shot động hoặc một dịch vụ reviewer khác.
- Reviewer phải phủ đúng danh sách đối tượng, suy ra đáp án độc lập, kiểm từng phần chấm và trích bằng chứng có thật. Nhãn kỹ năng được ẩn trong đầu vào review để giảm việc reviewer đồng ý với nhãn do người sinh bài đặt. Các câu nguyên nhân/suy luận có thêm lượt kiểm tra tập trung trong cùng ngân sách.
- Lỗi ngữ nghĩa cần được xác minh lại bằng nguồn. Một trích dẫn có thật chỉ chứng minh xuất xứ, không tự chứng minh kết luận. Một nhận xét sai hoặc diễn đạt chưa ưa thích không tự trở thành lỗi bắt buộc sửa. Các nhận xét chưa xác minh và lỗi định dạng reviewer được lưu riêng.
- Patch câu hỏi đóng phạm vi theo quan hệ question–meaning–requiredParts–evidence; patch bản dịch chỉ thay các lượt được phép. Kiểm tra lại toàn bộ ngân hàng và phần lân cận, giữ cả lỗi mới sau sửa. Có tối đa hai patch cho mỗi nhóm trong một job, kể cả khi nguồn được tạo lại.
- Có một lần sửa/tạo lại nguồn. Giữ nguyên các hoạt động trước phạm vi bị ảnh hưởng; tạo lại các phần phụ thuộc của hoạt động bị ảnh hưởng và các hoạt động sau nó. Không ghép câu hỏi hoặc bản dịch của nguồn cũ vào nguồn mới.
- GRADE kiểm khóa trước khi chấm. Khi khóa sai, sửa và kiểm tra lại khóa; giữ nguyên câu hỏi học viên đã thấy và transcript. Core xác minh nguồn, khóa gốc, phạm vi và lưu khóa đã sửa cùng điểm trong một giao dịch. Điểm không được lưu khi sửa hoặc kiểm chứng thất bại.
- Core kiểm tra chuỗi gọi, ngân sách, quyết định ACCEPTED, hash nguồn và hash nội dung cuối; V5.1 không được nhận bài có `qualityPassed=false`.

## Giới hạn công khai

GENERATE tăng từ tối đa 9 lên **17 lượt gọi logic**, gồm sáu lượt cơ sở và tối đa 11 lượt thêm. GRADE tăng từ một lên tối đa **7**; EXTRACT vẫn một. Mỗi job giữ deadline gốc 240 giây. Bản sửa, xác minh và sửa định dạng dùng chung ngân sách; hết ngân sách/deadline thì trả trạng thái chưa phục hồi, không tính thành công. Không tự gọi lại kết quả provider đã lỗi hoặc không rõ.

Prompt: `SCENARIO_MEANING_ASSESSMENT_V5_1`, hash `7c7172b2bbb04423c5ddb3a5e525dcae1d93189279afb22da1fe74184dea440e`. Policy: `scenario-meaning-harness.v4`. Chương trình local: giao tiếp phiên bản 7, TOEIC và IELTS phiên bản 6. V5.0 được đóng băng để đọc bài/job đã lưu; không sửa lịch sử hoặc tự chuyển pin của các job cũ. Đường phục hồi mới áp dụng cho V5.1, không hồi tố chấm lại các bài cũ.

Migration V54 mở ràng buộc lưu trữ mức dự trù lên 17; không bật hạn mức sử dụng hoặc xóa lịch sử. Đã kiểm trên H2 và PostgreSQL thật, gồm nâng từ các phiên bản 47/49/50/51/52/53 và cài mới. PostgreSQL dùng lock timeout 5 giây và statement timeout 30 giây; DDL vẫn cần khóa và kiểm tra bảng, không phải cam kết triển khai không khóa. Local đã sao lưu và kiểm tra danh mục archive trước khi nâng; 290 bản ghi dự trù cũ và tổng số lượt của chúng được giữ nguyên. Chưa kiểm thử khôi phục toàn bộ bản sao lưu này vào môi trường production.

## Đối chiếu lỗi thực

Đọc lại nguồn và artifact thay vì lấy nhận xét cũ làm nhãn chuẩn. Đã lưu riêng 15 quan sát được agent xác nhận và một nhận xét bị bác bỏ. Đây không phải nhãn chuyên gia, không phải kiểm định toàn bộ 66 bài. Bằng chứng gốc và trích dẫn chi tiết giữ ngoài Git; các định danh dưới đây tra được trong bộ dữ liệu local của đợt 66 lượt.

| Nhóm | Bằng chứng đã đối chiếu | Phục hồi và kết quả |
|---|---|---|
| Nguồn hỏng/sai yêu cầu | `cefr-a1-parcel`, a1/s3: cách viết số căn hộ hỏng | Source review → sửa nguồn → tạo lại phụ thuộc. Đã thành công thật, 9 lượt logic. |
| Khóa chấm ngoài câu hỏi | Khóa GRADE bị thêm địa điểm vào câu hỏi thời gian; có probe cũ trong báo cáo V5 | Sửa đồng bộ meaning/parts/evidence và khóa câu hỏi, giữ prompt. Probe GRADE thật và luồng QA qua Core đều sửa được trước khi lưu điểm. |
| Đáp án/meaning/evidence không đồng nghĩa; bằng chứng không chứng minh đáp án | Regression tổng hợp theo nguồn; các cảnh báo về sai đoạn ở báo cáo 66 lượt | Có patch theo phạm vi phụ thuộc, kiểm lại quote và ngữ nghĩa. Offline chứng minh đường sửa; chưa có tỷ lệ phát hiện chuẩn trên toàn bộ dữ liệu thật. |
| Sai người nói/hành vi lời nói | `ielts-basic-artclub`, a1/q2: nhân viên đánh vần tên nhưng câu hỏi gán cho giảng viên | Có xác minh nguồn và sửa question/key cùng nhau; regression có ca đúng đối ứng. Chưa kiểm thử lại mọi ca người nói/thỏa thuận bằng AI thật. |
| Hạn chót, phủ định, định lượng, mức chắc chắn | `cefr-b2-handover`, a2/q2; `ielts-standard-essay`, a3/q2: hạn chót bị đổi thành thời điểm chính xác | Regression có các cặp đúng/sai; không dùng regex làm phán quyết. Ca handover vẫn chưa phục hồi được trong giới hạn. |
| Câu nguyên nhân lặp tiền đề/câu hỏi tự lặp | `cefr-a1-plants`, a2/q2; `toeic-basic-boxes`, a2/q2; `toeic-basic-room`, a3/q2 | Có lượt kiểm tra tập trung và patch ngân hàng. Các bản thử trước vẫn bỏ sót; ca plants của bản cuối lỗi provider trước khi kiểm chứng xong. Ca boxes vẫn lọt qua ở bản cuối. |
| Nhãn kỹ năng sai/suy luận chỉ chép nguồn | `cefr-a2-volunteer`, a1/q2; handover a1/q3 và a3/q2; exhibit a3/q1; seeds a3/q1; survey a1/q1 | Phân loại support độc lập, đối chiếu nhãn và sửa nội dung thực. Handover đã thử hai patch nhưng không đạt; giữ từ chối, không tính sửa thành công. |
| Thuật ngữ/bản dịch | Pottery a3/q2; interview a2/q2; cảnh báo tiếng Việt ở các bài C2 cũ | Interview có patch câu hỏi thành công ở một bản thử. Bản thử cuối vẫn bỏ sót cách dịch sai trong câu trả lời pottery. Có probe đảo nghĩa bản dịch và regression giữ phủ định/phạm vi. |
| Reviewer báo sai | `cefr-a1-plants`: nhầm danh từ “bình tưới” với tên nhân vật | Bác bỏ sau khi đọc nguồn. Không dùng nhận xét này làm nhãn lỗi. Offline có kiểm tra phản bác reviewer sai, quote bịa và patch vượt phạm vi. |

## Đo thực tế

Các ca phát lại dùng artifact thực đã lưu, cố định nguồn/câu hỏi/bản dịch; review và repair gọi provider thật. Ba bước artifact được cấp lại không phải ba lượt inference mới. Các ca mới gọi cả EXTRACT và sinh bài thật. Giữ model pool/fallback hiện có, không đổi tài khoản để né lỗi và không xóa lượt thất bại.

| Bản thử trong quá trình phát triển | Đã chạy | Hoàn tất kỹ thuật | Lỗi |
|---|---:|---:|---:|
| Review ban đầu | 8 | 1 | 7 |
| Thêm inventory và đối chiếu từng part | 8 | 6 | 2 |
| Kiểm nguồn trước phụ thuộc | 8 | 7 | 1 |
| Ẩn nhãn kỹ năng | 3 | 2 | 1 |
| Bản cuối, ghép đúng các mẫu chưa gửi | 8 | 6 | 2 |
| **Tổng các lượt phát triển trên nhiều phiên bản** | **35** | **22** | **13** |

Tổng 22/35 chỉ là số pipeline hoàn tất qua các phiên bản, **không phải tỷ lệ đúng và không phải so sánh thống kê độc lập**. Có lặp đầu vào để kiểm thay đổi phiên bản; mọi kết quả cũ đều giữ nguyên. Ngoài ra có một lỗi chuẩn bị dữ liệu trước khi gọi AI và một lỗi vòng lặp thử nghiệm khiến một mẫu chưa được gửi; không coi chúng là kết quả inference.

Bộ cuối được cố định gồm năm ca artifact có lỗi đã đối chiếu và ba tình huống mới, mỗi yêu cầu chỉ gửi một lần. Hai manifest được ghép theo các mẫu chưa gửi; lượt provider lỗi không được phát lại:

- **6/8 hoàn tất kỹ thuật**. Một bài handover bị từ chối sau hai patch không đạt; một bài plants lỗi provider 503 sau fallback. Cả hai nằm trong mẫu số tám.
- **3/8 bài có thử sửa; 2/3 bài trong nhóm này được Harness chấp nhận sau sửa**: nguồn parcel và ngân hàng câu hỏi của tình huống giao tiếp mới. Đây là kết quả kiểm chứng nội bộ, chưa phải nhãn chuẩn về toàn bộ bài.
- Trong **5 ca lỗi cũ đã chọn**, chỉ parcel được phục hồi rõ ràng: **1/5**. Pottery và boxes vẫn được trả về với vấn đề đã biết (**2/5 còn lọt**); handover chưa sửa đạt (**1/5 từ chối**); plants chưa có kết quả nội dung (**1/5 provider lỗi**). Không tính từ chối là sửa thành công.
- Năm ca lỗi cũ có chất lượng ban đầu theo đối chiếu agent là **0/5 đạt**. Với ba tình huống mới chưa có nhãn chuyên gia cho chất lượng lần đầu; không suy ra từ việc JSON hợp lệ, reviewer PASS hoặc không phát sinh patch.

GRADE trực tiếp có **8/8 ca khớp kỳ vọng agent**: Việt, Anh, trộn ngôn ngữ, lỗi ngữ pháp nhỏ, sai, trống, thiếu một ý và khóa cố tình thừa yêu cầu. Các probe này dùng bản thử `f34035be…`; prompt GRADE không đổi ở bản chốt, và luồng Core bản chốt được kiểm lại bên dưới. Ở ca khóa hỏng, có đủ chuỗi review → xác minh → patch → review → GRADE; khóa thừa được loại trước khi trả CAPTURED. Các probe trực tiếp không lưu điểm học viên.

Luồng local qua **tài khoản QA 14 hiện có** đã tạo một bài và được Core nhận ACTIVE. Một probe tiếp theo chỉ cài lỗi khóa lên bài QA vừa tạo, lưu nguyên bản gốc làm bằng chứng: Core đã nhận khóa AI sửa, lưu khóa và điểm đúng trong cùng giao dịch; transcript và câu hỏi đã hiển thị không đổi. Hai phiên QA do kiểm thử mở đều đã logout. Không dùng tài khoản cá nhân hoặc tạo tài khoản mới.

Probe bản dịch cố tình đảo ý đạt **1/1**: lỗi được xác minh, patch đúng lượt, kiểm lại nguồn và trả bản dịch đúng ý; các lượt khác bằng nguyên bản. Dùng chín lượt logic, trong đó ba artifact đầu được cấp lại để cô lập đường sửa. Đây là một thử nghiệm riêng, không cộng vào mẫu số tám. Bằng chứng và kết quả chi tiết giữ ở `v51-recovery-translation-probe-20261116.json` trong thư mục local.

## Kiểm tra mã và giới hạn bàn giao

- Toàn bộ dịch vụ Python trên mã cuối: **973 đạt, 27 bỏ qua**. Bộ liên quan trực tiếp sau các thay đổi cuối cũng đạt. Test dùng mock chứng minh giao thức/scope/budget, không được trình bày như điểm chất lượng AI.
- Core: kiểm tra Scenario, ngân sách, Studio, nhận kết quả Python, từ chối nguồn trộn/quality false, lưu sửa khóa cùng điểm và migration PostgreSQL đã chạy. Các kỳ vọng về phiên bản chương trình và trần dự trù đã được cập nhật; không sửa migration cũ.
- Có kiểm tra patch không sửa được, reviewer sai, patch vượt phạm vi, lỗi mới sau sửa, hết deadline/ngân sách, nguồn đổi và giới hạn patch dùng chung qua các phiên bản nguồn.
- Chưa có nhãn chuyên gia, chưa chứng minh đủ tỷ lệ sửa đúng cho mọi nhóm, chưa kiểm toàn bộ tác động sư phạm hoặc tải cao. Việc hai lỗi đã biết vẫn lọt là hạn chế thực tế của bộ phát hiện hiện tại, không được che bằng tỷ lệ hoàn tất. Bài cũ vẫn giữ pin lịch sử.
- Production không được merge hoặc triển khai trong nhiệm vụ này. Push, CI và trạng thái chạy local được báo riêng khi bàn giao.

Lệnh kiểm tra chính từ thư mục dự án:

```powershell
python -m pytest services/ai-generation/tests -q --disable-warnings
mvn -B -q '-Dtest=Scenario*Test,AiUsageBudgetIntegrationTest,AiEvaluationStudioTest' '-Ddictalab.testcontainers=true' test
```

Các manifest, transcript, phiên thử, bản sao lưu và dữ liệu runtime chỉ nằm trong `C:/Users/HP/.dictalab/local/`; không đưa credential hoặc dữ liệu riêng vào Git. Các nguồn trước đó: [báo cáo 66 lượt](scenario-v5-large-evaluation-20261116.md) và [kiểm chứng V5.0](scenario-prompts-v5-verification.md).
