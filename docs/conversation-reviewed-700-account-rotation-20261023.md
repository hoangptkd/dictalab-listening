# Thu thập 700 bài với hai tài khoản Web

**Mới nhất 24/10: áp dụng low A1/A2 theo yêu cầu chủ dự án**, B1/B2 high mặc định.
Chiến dịch tiếp nối `data/fine-tuning/reviewed-500-v11-low-web-20261024` giữ
249 bài đạt/419 ứng viên, quota, lịch sử gửi và quyết định cũ. Các quy tắc Web
theo cặp nối tiếp, đổi account chỉ khi quota rõ, chống replay, Web chốt sau
kiểm tra cứng và không shutdown giữ nguyên. Xem
[cấu hình hiện tại](conversation-collection-v9-improvements-20261024.md).

**Mới nhất 24/10: chủ dự án bỏ Codex duyệt từng bài.** Chuyển sang bản tiếp nối
`data/fine-tuning/reviewed-500-v10-web-20261024`, giữ 244 bài đạt/411 ứng viên và
mọi quyết định cũ. Bài mới qua kiểm tra cứng + Web PASS đầy đủ thì tự chốt với
reviewer Web; false/null loại, thiếu bằng chứng/UNKNOWN chặn, không tự replay.
Không cần independent notes hoặc override. Giữ Web theo cặp, hai DeepSeek đồng
thời, high, không Gemini mới, không shutdown; tổng 500 = 400/48/52 rồi audit/SFT.
Quy tắc chuyển tài khoản và chống gửi trùng giữ nguyên. Các yêu cầu Codex duyệt
từng bài phía dưới là lịch sử. Xem [luồng hiện tại](conversation-collection-v9-improvements-20261024.md).

**Mới nhất 24/10 sau pilot:** tổng vẫn 500 bài; high mặc định + hai DeepSeek
in-flight bằng `--teacher-concurrency 2`, Web theo cặp nối tiếp, không Gemini mới.
Low A1/A2 chưa áp dụng vì pilot Codex đạt 7/8 so với high 8/8. Chủ dự án đã hủy
tắt máy: không shutdown/watchdog khi quota hoặc lỗi; checkpoint và báo nguyên nhân.
Các quyền shutdown bên dưới chỉ là lịch sử. Xem [báo cáo v9](conversation-collection-v9-improvements-20261024.md).

**Thay thế ngày 24/10:** chủ dự án chốt tổng **500 bài: 400/48/52**, dừng Gemini,
chấm Web theo cặp trong hội thoại mới sau phép thử thực tế. Chiến dịch hiện tại
`data/fine-tuning/reviewed-500-v9-deepseek-20261024`; đủ dữ liệu/audit thì SFT.
Một Web hết quota vẫn chuyển tài khoản; cả hai hết quota hoặc không thể thu thập
thêm thì checkpoint, báo cáo, dừng lịch và tắt máy theo quyền mới, không chờ qua đêm.
Chi tiết mới nhất tại [báo cáo v9](conversation-collection-v9-improvements-20261024.md).
Các quyết định trái với mục này bên dưới chỉ còn là lịch sử.

**Cập nhật 24/10/2026:** chủ dự án yêu cầu dừng lịch; automation đã `PAUSED`,
lô cuối đã kết thúc và không tự sinh thêm. Cấu hình v9 cho chiến dịch mới cùng
phân tích lỗi nằm trong [báo cáo cải thiện](conversation-collection-v9-improvements-20261024.md).
Chiến dịch v8 vẫn giữ nguyên artifact/manifest; không sửa hash để thay cấu hình.

## Quyết định đã chốt ngày 23/10/2026

- Train 125 bài/mức A1–B2, validation 25/mức, test 25/mức. Sinh mới toàn bộ.
- DeepSeek sinh cả nội dung và dịch trong một lời gọi; prompt collection v8, thinking bật.
- Web chấm từng bài; Codex kiểm tra độc lập và duyệt cuối, lưu căn cứ riêng từng fact.
- Giữ trần 1.500 ứng viên cho bộ chính dù registry tắt giới hạn vận hành.
- Không repair target. Loại bài sai/chưa chắc chắn rồi sinh bài bù; không dùng mẫu diagnostic.
- DeepSeek HTTP 402/429 hoặc quota pool hết: lưu checkpoint, dừng thu thập và báo cáo.
- Web xác nhận quota: gửi **cùng yêu cầu chấm đã lưu** sang tài khoản còn lại.
  Timeout, HTTP 429 không có mã quota xác định, lỗi mạng hoặc JSON hỏng không cho phép tự gửi lại.
- Hai Web cùng hết quota: lưu `WAITING_WEB_QUOTA` và `retryAt`; thử lại sau 15 phút.
  Khoảng này là lịch kiểm tra lại, không phải thời điểm reset được nhà cung cấp bảo đảm.
  Bridge kiểm tra thông báo UI trước khi gửi; nếu vẫn bị giới hạn thì tiếp tục chờ.
- Cho phép model khác giữa tài khoản. Biên bản giữ account ID, nhãn model UI nếu có,
  alias phản hồi và giới hạn xác minh; không coi alias `chatgpt-web` là tên model thật.
- Mỗi profile có một tab, mỗi bài mở hội thoại mới trên tab đó; dọn tab trống/mồ côi.
- Không tự tắt máy; chưa chạy train trong giai đoạn thu thập.

## Profile và bridge

1. Account 1 giữ profile local cũ ở `data/chatgpt-api-web/data/chrome-profile`.
2. Account 2 giữ profile ngoài dự án ở `%LOCALAPPDATA%/DictaLab/chatgpt-judge/account-2`.
3. CDP chỉ listen loopback; hai bridge HTTP lần lượt ở `127.0.0.1:3000` và `:3001`.
4. Mỗi bridge có thư mục dữ liệu/hội thoại riêng. Không sao chép cookie hoặc đăng nhập giữa profile.
5. Bản sửa local của chủ dự án được giữ nguyên trước khi bổ sung thay đổi tài khoản.
   `scripts/chatgpt-web-accounts.patch` là phần bổ sung cho bridge đã có sửa composer/tab;
   chỉ áp dụng sau `git apply --check`, không ghi đè khi xung đột.

Mở lại profile đã đăng nhập nếu Chrome bị đóng:

```powershell
agent-browser --session dictalab-judge-account-1 --headed --profile C:/Users/HP/Desktop/English_dictition/data/chatgpt-api-web/data/chrome-profile open https://chatgpt.com/
agent-browser --session dictalab-judge-account-2 --headed --profile C:/Users/HP/AppData/Local/DictaLab/chatgpt-judge/account-2 open https://chatgpt.com/
python -X utf8 scripts/start-chatgpt-judge-accounts.py
```

Launcher chỉ dùng Chrome đang mở, đọc cổng CDP từ profile, kiểm tra protocol/account/tab.
Nếu bridge cũ chưa nạp bản sửa, báo cần restart có kiểm tra; không tự kill tiến trình lạ.
Nếu Chrome đóng trong lúc chạy, dừng xử lý để mở lại đúng profile và khởi động bridge
với cổng CDP mới; không xem mất kết nối là hết quota.

## Thu thập và duyệt

Chiến dịch đã tạo, **không chạy prepare lại vào cùng thư mục**:

```powershell
python -X utf8 -m training.collect_reviewed status --campaign data/fine-tuning/reviewed-700-v8-20261023
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/reviewed-700-v8-20261023 --limit 8
```

Mỗi batch dừng để Codex duyệt thật. Đọc nguồn, ngữ cảnh, câu hỏi, đáp án, dịch và
lịch sử trước khi đối chiếu Web. Không tự coi Web PASS là approved. Duyệt qua
`adjudicate --decision-file ...` với hash bài/ngữ cảnh/Web và trích dẫn từng fact;
mỗi lỗi Web bị bác có căn cứ riêng. Khi còn pending, không sinh lô mới.

`*.web-attempt-NNN.json` giữ endpoint, account, request hash và outcome trước/sau HTTP.
Chỉ các attempt `QUOTA` có hash khớp mới được tiếp tục. `UNKNOWN` phải xử lý thủ công.
Bài đạt giữ đầy đủ; bài loại xóa chi tiết sau chốt, chỉ giữ hash/trạng thái/nhóm lỗi/usage.

Khi `WAITING_WEB_QUOTA`, gọi collect sau `retryAt` chỉ chấm lại bài đã lưu, không gọi
DeepSeek. Khi `WAITING_DEEPSEEK_QUOTA`, collect không tự thử lại; chờ chủ dự án xử lý
quota và yêu cầu tiếp tục. Không sửa hash manifest hay xóa sent-index để chạy lại.

Đủ 700 bài đã duyệt và không còn pending thì audit/export bằng lệnh export hiện có.
Không dùng test để sửa prompt hoặc chọn checkpoint.

## Kiểm tra

```powershell
node scripts/test-chatgpt-web-tabs.cjs
node scripts/test-chatgpt-web-accounts.cjs
python -X utf8 -m pytest training/tests services/ai-generation/tests/test_provider_registry.py services/ai-generation/tests/test_model_gateway.py -q --disable-warnings
```

Quota failover được kiểm định bằng mock; không cố tình tiêu hết quota để thử.
Kết quả thực tế và số bài duyệt phải lấy từ checkpoint/decisions của chiến dịch,
không suy ra từ vòng thử v8 trước đó.
