# Quy tắc kiểm duyệt bài nghe tình huống

Tài liệu mô tả mã nguồn few-shot V7, không khẳng định runtime đang chạy đã cập nhật. Backend vẫn từ chối khi kiểm tra cấu trúc hoặc bằng chứng thất bại. Kết luận và lỗi định dạng đầu ra của AI reviewer được lưu để tham khảo, không tự động từ chối bài. Khi một reviewer không đạt hoặc trả sai định dạng, `qualityPassed` là `false` và `reviewAdvisory` lưu kết quả tương ứng. Lỗi kết nối hoặc timeout tới nhà cung cấp vẫn có thể khiến tác vụ thất bại. Những job pin phiên bản cũ giữ chính sách cũ.

## V7 — rút gọn chỉ dẫn từng bước — 04/11/2026

- EXTRACT giữ nguồn dữ kiện, vai và giả định; không giải thích dài từng chương trình hay nhắc lặp mục tiêu khai báo không phải điểm đã đánh giá.
- GENERATE giữ tính nhất quán và tiến triển theo thể loại/mục tiêu; bỏ danh sách diễn biến và các câu vá riêng cho đặt chỗ, thanh toán, deadline, lập trước đáp án.
- QUESTIONS dùng kỹ năng trong profile; bỏ danh sách skill, giải thích nội bộ quality gate và các ví dụ dài về thỏa thuận. Vẫn phải có bằng chứng và phân biệt mức chắc chắn.
- REVIEW_QUESTIONS bỏ hẳn `atomicMeaning` trong chỉ dẫn, ví dụ và schema mới; còn chín cờ. Không chèn giá trị mặc định cho trường đã bỏ. Schema cũ vẫn dùng cho job ghim phiên bản trước.
- TRANSLATE gộp yêu cầu giữ nghĩa; REVIEW dùng năm rubric ngắn gọn thay cho danh sách lỗi riêng của từng tình huống. GRADE V5 không đổi.
- Phần chỉ dẫn trước ví dụ của sáu bước giảm từ 17.445 xuống 8.146 byte UTF-8 (53,3%). Tổng sáu prompt có few-shot giảm từ 59.406 xuống 47.029 byte (20,8%); giảm 41,4% so với 80.196 byte trước lần tối ưu few-shot. Đây là số byte, chưa phải token nhà cung cấp hoặc bằng chứng chất lượng AI tăng.
- Tái sử dụng chín ví dụ V2, bỏ trường reviewer đã nghỉ dùng trên bản sao trong bộ nhớ và ghi fingerprint bộ ví dụ V3. Không sao chép thêm một tệp JSON lớn. Đầu ra mẫu vẫn đầy đủ schema mới và có cả ví dụ đạt lẫn không đạt.

## V6 — few-shot gọn hơn — 04/11/2026

- Bộ ví dụ `scenario_personalized_fewshot_examples_v2.json` có 9 mẫu được gửi, thay cho 12 mẫu ở bản trước. Đây là mẫu do agent soạn, chưa phải đáp án chuẩn do con người phê duyệt.
- Các ngữ cảnh ví dụ chỉ giữ trường cần cho từng bước; bỏ hash, phiên bản, nguồn thang điểm, metadata hiệu chỉnh và thông số âm thanh. TRANSLATE không lặp ngữ cảnh học. REVIEW_QUESTIONS không lặp hai bản cùng câu hỏi. Đầu vào thực tế vẫn có đầy đủ ngữ cảnh đã được backend kiểm tra.
- Đầu ra mẫu vẫn đầy đủ schema: ba activity, sáu câu hỏi hoặc sáu kiểm tra khi bước đó yêu cầu, mười cờ REVIEW_QUESTIONS và năm rubric REVIEW.
- REVIEW_QUESTIONS có một mẫu đạt gồm câu hỏi hai chi tiết có căn cứ và một mẫu không đạt với ba lỗi: quote nằm ở activity khác, hai câu hỏi trùng ý và nhầm đề xuất thành thỏa thuận. REVIEW cũng giữ cả mẫu đạt và không đạt.
- Tổng dung lượng UTF-8 của sáu system prompt giảm từ 80.196 xuống 59.406 byte (25,9%). Đây là số byte đo trực tiếp, không phải số token hay bằng chứng chất lượng AI tăng. Riêng REVIEW_QUESTIONS dài hơn do thêm mẫu lỗi; tổng sáu bước ngắn hơn. GRADE giữ nguyên bản V5.
- Các mẫu sai activity và nhầm agreement còn được backend chặn trước reviewer trong luồng bình thường. Ví dụ reviewer minh họa cách đánh giá khi kiểm tra riêng; không biến reviewer trở lại thành điều kiện chặn xuất bản.

## Đã thay đổi trong V3

- Số từ tối thiểu/tối đa mỗi activity và tối đa cả bài chỉ là hướng dẫn viết theo profile; Python và Core không từ chối V3 vì số từ vượt hoặc thiếu ngưỡng. Các giới hạn ký tự/segment bên dưới vẫn áp dụng.
- Một câu hỏi có thể yêu cầu nhiều thông tin. Đáp án phải trả lời đầy đủ, bằng chứng hỗ trợ các thông tin đó. `atomicMeaning` là tên trường cũ giữ tương thích, V3 yêu cầu AI dùng nó để kiểm tra câu rõ ràng và có thể trả lời, không dùng để cấm nhiều thông tin. Reviewer vẫn có thể từ chối nếu đánh giá sai.
- Ví dụ reviewer cũ dạy từ chối câu hỏi do gộp thông tin được bỏ khỏi prompt V3. Job cũ giữ chính sách theo pin cũ.

## 1. EXTRACT — phân tích yêu cầu

**Backend:** đúng hai nhân vật với ID khác nhau; người nghe không gắn vào nhân vật, người tham gia phải gắn vào vai có thật trong đặc tả. Yêu cầu hợp lệ phải có ít nhất một trích dẫn đầu vào; từng trích dẫn thông tin do người dùng cung cấp và suy luận phải có nguyên văn trong đầu vào. Không được trùng nội dung khai báo. Tối đa sáu trích dẫn do người dùng cung cấp, bốn suy luận và bốn giả định theo schema; tổng suy luận và giả định không quá bốn. Các trường phải theo định dạng, độ dài và danh sách nhãn schema.

**Prompt:** giữ vai, quan hệ, điều kiện, thời gian, số lượng và độ chắc chắn; phân biệt dữ kiện người dùng, suy luận và giả định. Có thể chọn bối cảnh hư cấu nếu yêu cầu chỉ mô tả kỹ năng. Từ chối yêu cầu thực sự không an toàn hoặc không thể hiểu.

## 2. GENERATE — hội thoại

**Backend:** đúng ba activity theo thứ tự a1–a3, mỗi activity có 2–6 segment và cả hai nhân vật. ID segment không trùng trong activity; speaker phải thuộc đặc tả. Mỗi đoạn tiếng Anh có 1–600 ký tự. Không loại V3 theo số từ.

**Prompt:** các phần liên kết; giữ vai và dữ kiện đã cho, phân biệt sự thật, ý kiến, giả định, đề xuất và việc đã đồng ý. Có thể thêm chi tiết hợp lý phục vụ bối cảnh. Không bắt buộc mọi hội thoại có giao dịch hoặc kết thúc bằng thỏa thuận. Từ vựng, cú pháp, mật độ và suy luận theo profile; đủ nội dung phục vụ mục tiêu nghe. Chiều dài mục tiêu là hướng dẫn mềm.

## 3. QUESTIONS — câu hỏi, đáp án và bằng chứng

**Backend:** đúng q1 và q2 trong mỗi activity; kỹ năng thuộc profile, ít nhất một câu đạt focusSkill. Sáu factEn không được trống hoặc trùng nguyên văn sau chuẩn hóa. Bằng chứng gồm 1–3 trích dẫn chính xác từ segment của cùng activity. Nhãn suy luận phải hợp chính sách profile và có giải thích; nhãn khác không được chứa giải thích suy luận. Câu gắn agreement phải có trích dẫn theo thứ tự từ cả hai người. Một số cách hỏi về proposal bị chặn nếu khẳng định hai bên đã đồng ý. Câu hỏi tối đa 300 ký tự, đáp án 200, factEn 300, mỗi trích dẫn 400.

**Prompt:** câu hỏi có thể gồm nhiều thông tin, đáp án bao phủ đủ các phần. Bảo toàn điều kiện, phủ định và mức chắc chắn; không biến đề xuất thành thỏa thuận. Không hỏi trùng nghĩa, không lộ đáp án, không dựa vào kiến thức ngoài. Suy luận phải có căn cứ và không có đáp án cạnh tranh hợp lý. Người học có thể trả lời tiếng Việt, tiếng Anh hoặc kết hợp.

## 4. REVIEW_QUESTIONS — AI xét câu hỏi

AI trả đủ sáu bản kiểm, lý do, đơn vị đáp án và chín cờ: `answerSupported`, `distinctMeaning`, `factSupported`, `questionMatchesFact`, `speechActSupported`, `noAnswerLeak`, `vietnameseNatural`, `skillAppropriate`, `groundingSupported`. Để reviewer được ghi nhận là đạt, cần đủ và đúng ID, câu mô tả nghĩa khác nhau sau chuẩn hóa, ít nhất một answerUnitsVi không rỗng, các cờ true, lý do không rỗng, `valid=true` và `issues=[]`. Không đạt các điều kiện này chỉ tạo cảnh báo, không tự chặn xuất bản. Schema hiện cho tối đa bốn answerUnitsVi mỗi câu. Reviewer có thể nhận diện sai; kết luận đạt không chứng minh nội dung đúng.

## 5. TRANSLATE và REVIEW — bản dịch và nội dung

**Backend:** có đúng một bản dịch cho mỗi segment, không thiếu/trùng ID; mỗi bản dịch 1–800 ký tự và không được sao nguyên văn một câu tiếng Anh có ít nhất ba từ. Để REVIEW được ghi nhận là đạt, cần đúng năm rubric `SOURCE_ROLES`, `DIALOGUE`, `TRANSLATIONS`, `PROFILE`, `LEARNING_GOAL`, mỗi rubric một lần; đúng một bản kiểm cho mỗi bản dịch. Tất cả rubric và bốn cờ của từng bản dịch phải đạt, lý do không rỗng, `valid=true` và không có issues. Kết luận không đạt hoặc sai định dạng của reviewer được lưu làm cảnh báo.

**Prompt:** AI kiểm vai và dữ kiện gốc; tính liên tục, logic và mức phù hợp profile; mục tiêu học; nghĩa, thuật ngữ, tiếng Việt và độ tự nhiên của từng bản dịch. Các mức thời gian, phủ định và độ chắc chắn cần được giữ. Tên riêng và từ viết tắt phù hợp được phép giữ; từ tiếng Anh thông thường chưa dịch có thể bị reviewer từ chối.

## 6. GRADE — chấm câu trả lời

Prompt GRADE V5 nhận câu trả lời bằng tiếng Anh, tiếng Việt hoặc kết hợp, xem xét ý nghĩa và chấp nhận diễn đạt tương đương. Không có hạn chế một ý: `CAPTURED` khi đúng đủ thông tin được hỏi; `PARTIAL` khi đúng một phần nhưng phần còn lại thiếu hoặc sai; `INCORRECT` khi trả lời sai và không đúng thông tin nào được hỏi; `MISSED` khi không có câu trả lời liên quan; `UNCERTAIN` khi chưa xác định được ý nghĩa câu trả lời. Ví dụ hỏi giờ và địa điểm gặp, trả lời đúng địa điểm nhưng sai giờ phải là `PARTIAL`. Backend yêu cầu đúng hai ID câu hỏi. Đoạn evidenceVi nếu có phải trích nguyên văn câu người học; các trạng thái CAPTURED/PARTIAL/INCORRECT cần bằng chứng không rỗng.

## Các điểm có thể còn cứng nhắc để xem xét

Số activity/segment/câu hỏi cố định; tối đa ba trích dẫn evidence và bốn đơn vị đáp án; bằng chứng phải trích nguyên văn cùng activity. Reviewer vẫn đánh giá các cờ, rubric, trùng nghĩa, lộ đáp án, kỹ năng, độ khó, bản dịch và sự nhất quán, nhưng kết luận chỉ mang tính tham khảo. Bài có thể được xuất bản dù reviewer cảnh báo lỗi nội dung; cần xem lại cảnh báo khi đánh giá chất lượng. Chính sách độ khó vẫn được reviewer xét dù số từ không còn là ngưỡng backend.
