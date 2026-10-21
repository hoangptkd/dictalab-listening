# Hội thoại luyện nghe AI — V1

> Historical implementation record from the October 2026 cutover. The current cloud text provider is
> Google Gemini; see `docs/ai-platform-runbook.md` and `docs/current-system-reference.md` for current
> configuration. References to ShopAIKey below document the earlier deployment and are not current setup.

## Bản sửa local ngày 03/10/2026 — câu hỏi thử thách hơn

- A1 giữ hai ý được hỏi, thêm thay đổi nhỏ hoặc phương án bị loại; A2 nối chi tiết giữa các câu; B1 nghe điều kiện, lý do hoặc kế hoạch sửa đổi. Không tăng độ khó bằng từ vựng ngoài trình độ hay suy luận mơ hồ.
- Câu hỏi tách rõ ngày/giờ/địa điểm/lý do. Chấm yêu cầu đúng thông tin cuối cùng và đúng quan hệ được hỏi; từ khóa đơn lẻ không đủ, nhưng vẫn chấp nhận cách diễn đạt tương đương, số viết bằng chữ/số và lỗi gõ nhỏ.
- Sau kiểm tra cấu trúc, có một yêu cầu AI riêng kiểm tra câu hỏi–đáp án, ý thừa/thiếu, phương án gây nhiễu, thông tin không có trong đoạn nghe và đáp án lặp ở lượt 4. Dùng model tạo câu hiện có; không thêm nhà cung cấp. Câu không đạt được sửa tối đa một lần, sau đó từ chối xuất bản. Lỗi kết nối không tự retry. Đây vẫn là đánh giá bằng AI, không bảo đảm mọi lỗi ý nghĩa đều bị phát hiện.
- Gửi câu trả lời chỉ chấm và lưu phản hồi. Bấm tiếp tục mới tạo/kiểm tra câu tiếp theo; Cartesia vẫn chuẩn bị audio ở nền sau khi câu được lưu. Phản hồi chấm không chờ tạo câu mới. Phiên cũ có `nextPrompt` vẫn được kiểm tra lại trước khi dùng.
- Mỗi giờ dọn WAV của tối đa 100 phiên được tạo quá `CONVERSATION_AUDIO_RETENTION_DAYS` ngày (mặc định 30); bỏ qua phiên có lease còn hiệu lực. Dọn cả phiên bỏ dở để tránh audio tích lũy. Lịch sử, câu trả lời, câu nghe và kết quả giữ nguyên. Tiếp tục phiên cũ sẽ tạo lại audio khi bấm nghe; phiên cũ đã mở lại có thể cần tạo audio lại sau lần dọn tiếp theo. Giá trị <=0 tắt dọn audio. Không đổi schema.
- Kiểm chứng local: 25 test hội thoại + 5 test transport ShopAIKey qua; 5 test JavaScript hội thoại qua; UTF-8 qua. Bao gồm từ chối/sửa câu sai ý, review lỗi/malformed không xuất bản, chấm không gọi generator, retry, ownership, audio cũ được tạo lại và giữ audio ở phiên đang xử lý.
- Thử provider thật với dữ liệu tổng hợp: tạo và kiểm tra một câu mỗi mức A1/A2/B1, khoảng 3,92/4,48/4,15 giây cho tạo + review trong một đợt chạy song song. Cả ba mẫu đạt review, 30/33/32 từ. Đây là mẫu nhỏ, không phải SLA hoặc đánh giá hiệu quả học. Bài chấm đối chứng: quán cạnh ga lúc 11 giờ được CAPTURED cả hai ý; quán đối diện công viên lúc 10 giờ bị INCORRECT cả hai, có gợi nghe lại mà không tiết lộ đáp án. Không ghi phiên học vào database và chưa deploy bản sửa này.

## Luồng hiện tại trong mã nguồn — chuyển toàn bộ AI văn bản sang ShopAIKey

Bản chuyển nhà cung cấp **đã deploy VPS ngày 02/10/2026, khoảng 23:10 (UTC+7)**. Các mục lịch sử bên dưới mô tả bản production trước đó, không phải luồng AI của bản mới.

- `/conversation`: chọn một trong năm tình huống và độ khó A1, A2 hoặc B1.
- Mỗi phiên có ba nhiệm vụ nghe và một câu mới để kiểm tra khả năng chuyển sang thông tin khác.
- ShopAIKey dùng `DICTALAB_CONVERSATION_MODEL` (mặc định `gemini-2.5-flash`) tạo nội dung; chấm ý hiểu và tạo lời làm rõ qua `SHOPAIKEY_MODEL` (mặc định `claude-sonnet-4-5`). Không chấm chính tả, phát âm hoặc khả năng nói. Cartesia giữ nguyên.
- Khi chưa đủ bằng chứng, AI hỏi xác nhận. Mỗi nhiệm vụ tối đa ba lượt phản hồi.
- Cartesia tạo WAV tiếng Anh. Nghe lại và nghe chậm 0,8× dùng audio đã lưu.
- Transcript/bản dịch chỉ được trả về sau thao tác mở hỗ trợ; kết quả phân biệt ý hiểu có/không dùng hỗ trợ.
- Câu trả lời được lưu trước khi chấm. Lỗi nhà cung cấp có thể thử lại với cùng requestId, không tạo thêm lượt.

Hội thoại chỉ cần `DICTALAB_CONVERSATION_ENABLED=true`, khóa ShopAIKey và Cartesia; không phụ thuộc công tắc Coach. Mỗi request gửi rõ prompt/attempts/context cần thiết từ database, không có session/tab ChatGPT. Coach cũng dùng client ShopAIKey chung, vẫn có công tắc riêng và fallback theo quy tắc. Timeout ShopAIKey: 60 giây cho chấm/làm rõ/Coach, 30 giây mỗi lần sinh/sửa câu. Lease hiện có 210 giây vẫn được giữ; không thay dữ liệu/lịch sử/migration.

`scripts/run-local.ps1 -WithCoach` không chạy browser nữa. `scripts/deploy-to-vps.ps1` chỉ backup DB và cập nhật JAR, không yêu cầu bridge. Các script/patch bridge cũ chỉ còn phục vụ đọc lịch sử/rollback JAR cũ, không cần chạy cho bản mới. Khóa chỉ đặt trong môi trường backend, không đưa vào source hay frontend.

Kiểm chứng đợt chuyển này: `mvn clean verify` thành công, 139 bài Java (138 qua, một benchmark bỏ qua có chủ đích); 72/72 bài JavaScript qua. Trước deploy đã chạy lại toàn bộ bằng `mvn clean package` và kiểm thử JavaScript, cùng kết quả. Kiểm tra cú pháp PowerShell và UTF-8 đạt. Kiểm thử transport dùng HTTP server giả lập; chấm/Coach/làm rõ dùng dữ liệu tổng hợp và mock ShopAIKey để xác minh schema, ngữ cảnh, idempotency, ownership và retry. Artifact trên VPS đã xác nhận không chứa `ChatGptWebCoachClient`.

### Kiểm chứng triển khai toàn bộ ShopAIKey

- Phép thử thật trên VPS với `claude-sonnet-4-5`, chỉ yêu cầu JSON `ready=true`, thành công trong 24,14 giây. Không gửi dữ liệu học thật và không ghi phiên học production. Đây là kiểm tra kết nối/khóa/model, không phải kiểm chứng chất lượng chấm đầy đủ hoặc SLA.
- Backup trước cập nhật: `dictalab-20261002-230900.dump`, 4.855.634 byte; đã đọc danh mục bằng `pg_restore --list`, chưa restore drill.
- Giữ cả hai JAR cũ trong `C:\DictaLab\backups\shopaikey-cutover-20261002-230707`; updater cũng giữ `dictalab.previous.jar` để rollback. Không xóa profile hoặc dừng task bridge cũ, nhằm giữ lựa chọn rollback JAR dùng Web.
- SHA-256 bản chạy: `D3239901627DCE8C0FFDFC12F90595C4A99A062CBDFB44D4DCF815290D049D7A`, đã đối chiếu trên VPS.
- Service Running, health loopback/public UP. Public `/`, `/conversation`, `/js/conversation.js`, `/js/coach.js` trả 200; API `/api/v1/conversation` trả 401 nếu chưa đăng nhập. Chưa kiểm thử toàn bộ tương tác bằng tài khoản người học qua trình duyệt trong đợt deploy này.

## Lịch sử: thay đổi sinh câu ngày 02/10/2026 — đã triển khai VPS trước đợt chuyển toàn bộ AI

- Model tạo hội thoại riêng: `DICTALAB_CONVERSATION_MODEL=gemini-2.5-flash`. Khóa và URL dùng `SHOPAIKEY_API_KEY`/`SHOPAIKEY_URL` hiện có. Không đổi `SHOPAIKEY_MODEL` của bài Listening Comprehension.
- Hội thoại cần đủ ShopAIKey, ChatGPT Web và Cartesia; thiếu khóa ShopAIKey thì catalog báo chưa sẵn sàng, không tạo phiên lỗi ngay từ đầu.
- Backend bắt buộc câu nghe có 20–65 từ, đúng schema, 2–3 ý độc lập và evidence xuất hiện trong câu. A1 bắt buộc đúng hai ý. Lượt 4 từ chối fact trùng văn bản đáp án hoặc evidence sau khi chuẩn hóa chữ hoa/thường và dấu câu; so sánh này không phát hiện mọi cách diễn đạt đồng nghĩa.
- Prompt tạo câu yêu cầu một ý được hỏi trên mỗi fact; không ghép thêm lý do, ngày, địa điểm hoặc kích cỡ ngoài câu hỏi. Prompt chấm yêu cầu bỏ qua chi tiết thừa trong fact cũ: nếu chỉ hỏi phương tiện, trả lời “xe buýt” không phải bổ sung lý do.
- Đầu ra bị bộ kiểm tra từ chối được yêu cầu sửa tối đa một lần, cùng tình huống và lịch sử. Không tự gọi lại khi HTTP/network lỗi, không chuyển sang Web để tạo câu. API hội thoại có timeout 30 giây mỗi lần; lease 210 giây bao phủ timeout chấm tối đa 120 giây, hai lần API và phần lưu dữ liệu.
- Câu chuẩn bị sẵn từ phiên cũ nếu không đạt kiểm tra mới sẽ được thay thế; không làm nút chuyển nhiệm vụ mắc kẹt ở một câu không hợp lệ.
- Tái sử dụng client và thư viện có sẵn theo Ponytail, không thêm dependency hoặc migration. Kiểm tra UTF-8 được thực hiện trên mã, cấu hình và tài liệu sửa đổi.

### Kiểm chứng bản sửa

- `mvn test`: 137 bài, 136 qua, 1 benchmark chủ động bỏ qua; không có failure/error.
- `node --test src/test/js/*.test.cjs`: 65/65 qua.
- PostgreSQL 18.4 trong cụm local riêng: 21/21 bài liên quan hội thoại và client qua; cụm thử được dừng sau khi chạy, không sử dụng database của ứng dụng hoặc VPS.
- Thử thật prompt cuối qua ShopAIKey trên ba đầu vào tổng hợp A1/A2/B1: 1,833 / 2,297 / 2,528 giây, câu 28 / 38 / 23 từ, cả ba đạt bộ kiểm tra và không cần lượt sửa. Đây là phép thử nhỏ, không phải bảo đảm độ trễ hay chất lượng lâu dài; chưa tính Cartesia.
- Thử chấm thật qua bridge: câu hỏi chỉ hỏi vị trí và phương tiện; fact cố tình chứa thêm lý do “vì đi bộ quá xa”. ChatGPT chấm câu trả lời “Hiệu thuốc ở cạnh ngân hàng, nên đi xe buýt.” là CAPTURED ở cả hai ý, evidence lấy đúng từ câu trả lời, không đòi lý do. Một mẫu không chứng minh mọi trường hợp chấm đều đúng.
- Các thử AI dùng dữ liệu tổng hợp, không tạo phiên học thật hoặc ghi dữ liệu production. Bản sửa được triển khai lên VPS ngày 02/10/2026; backend và bridge đã được khởi động lại theo quy trình có sao lưu và rollback.

Không coi kiểm tra JSON hoặc vài ví dụ thành công là đủ bảo đảm chấm đúng mọi trường hợp. Cần tiếp tục bổ sung bộ câu trả lời đúng/thiếu/sai và diễn đạt lại khi vận hành, phù hợp hướng dẫn [OpenAI Docs về đánh giá](https://developers.openai.com/api/docs/guides/evaluation-best-practices).

## Tối ưu Chrome và thời gian chờ

- Mỗi phiên học gửi `session_id` riêng cho bridge. Các lượt chấm và làm rõ dùng lại cùng hội thoại/tab; không chia sẻ lịch sử giữa người học. ShopAIKey không dùng tab Chrome; mỗi lần tạo câu nhận lịch sử gồm câu nghe, kỹ năng, facts và kết quả chấm, không gửi toàn bộ trạng thái phiên.
- Bridge giữ tối đa 4 tab hội thoại được quản lý, đóng tab không hoạt động 10 phút và đóng tab khi phiên hoàn thành. Khi cần tiếp tục, bridge mở lại hội thoại đã lưu. Việc dọn tab dùng chung hàng đợi với yêu cầu AI để không đóng tab đang sinh câu trả lời.
- Yêu cầu chấm Web chỉ trả kết quả chấm. Khi lượt hiện tại kết thúc và còn nhiệm vụ, backend gọi Flash chuẩn bị câu tiếp theo; lỗi tạo câu không làm mất kết quả chấm hợp lệ. Câu chuẩn bị này không được gửi xuống trình duyệt; nút chuyển nhiệm vụ dùng nội dung đã lưu mà không gọi AI lần nữa. Nếu câu bị thiếu hoặc không hợp lệ, nút chuyển nhiệm vụ gọi Flash để tạo lại.
- Audio được tạo nền sau khi lưu câu nghe, câu tiếp theo chuẩn bị sẵn hoặc câu làm rõ. Có tối đa 2 tác vụ TTS đang chạy và 8 tác vụ chờ. Bấm nghe dùng chung tác vụ đang chạy, không gửi yêu cầu TTS trùng. Lỗi tạo audio không làm mất nhiệm vụ/kết quả chấm; có thể thử nghe lại.
- Không tăng số yêu cầu ChatGPT đồng thời: backend và bridge vẫn xử lý một yêu cầu Web tại một thời điểm. Tạo câu qua API không chiếm hàng đợi Web. Tốc độ chấm/làm rõ vẫn phụ thuộc bridge; tốc độ tạo câu không đại diện cho toàn bộ thời gian người học chờ.

Khi cập nhật VPS, triển khai backend cùng `scripts/chatgpt-web-compat.patch`, `scripts/apply-chatgpt-web-patch.ps1` và script khởi động bridge. Khởi động lại bridge và backend để nạp thay đổi. Script áp dụng bản vá hỗ trợ nâng cấp bridge đã có bản vá cũ; nếu xung đột với chỉnh sửa riêng, nó giữ nguyên file đã cài và báo lỗi.

`scripts/deploy-to-vps.ps1` chạy kiểm thử trước khi đóng gói, sao lưu PostgreSQL, cập nhật bridge rồi cập nhật JAR. `scripts/update-coach-bridge-vps.ps1` kiểm tra SHA-256/cú pháp, giữ bản bridge cũ để phục hồi khi lỗi và bỏ giới hạn chạy 72 giờ của tác vụ bridge. Bản cài VPS không có Git dùng `index.js` đã được vá và kiểm tra trước khi tải lên; script khởi động chỉ tự áp dụng patch cho checkout có `.git`. Hồ sơ đăng nhập Chrome được giữ nguyên. Cấu hình khóa Cartesia và bật Hội thoại AI nằm riêng trong môi trường dịch vụ, không được ghi vào bản build.

### Chrome trên VPS

`scripts/start-coach-bridge-vps.ps1` mặc định chạy Chrome headless (không hiện cửa sổ), dùng cùng hồ sơ `C:\DictaLab\coach-bridge\data\chrome-profile`. Không phải đăng nhập lại cho từng yêu cầu. Chế độ [headless của Chrome](https://developer.chrome.com/docs/automation-and-testing/headless) giảm phần hiển thị cửa sổ; mức giảm CPU/RAM cần đo trên VPS, không đảm bảo AI sinh nội dung nhanh hơn.

Trên VPS hiện tại, lần kiểm tra ngày 01/10/2026 gặp trang xác minh Cloudflare khi dùng headless. Tác vụ `DictaLabCoachBridge` đang dùng `-VisibleChrome`; chế độ này đã gửi/nhận hai lượt JSON và dùng lại cùng hội thoại thành công. Updater giữ chế độ đã cấu hình trong tác vụ để lần deploy tiếp theo không tự đổi lại sang headless. Chỉ bỏ `-VisibleChrome` sau khi kiểm tra ChatGPT hoạt động thực tế trong chế độ ẩn.

1. Khi đăng nhập lần đầu hoặc ChatGPT yêu cầu xác minh, chạy:

   ```powershell
   pwsh -File scripts/start-coach-bridge-vps.ps1 -VisibleChrome
   ```

   Đăng nhập ChatGPT trong cửa sổ Chrome của bridge.

2. Dừng bridge rồi đóng Chrome của bridge trước khi đổi chế độ. Chrome đã chạy ở CDP 9223 được tái sử dụng, nên chỉ khởi động lại Node không đổi được chế độ Chrome. Không xóa thư mục hồ sơ.
3. Chạy bình thường để dùng headless:

   ```powershell
   pwsh -File scripts/start-coach-bridge-vps.ps1
   ```

Log tiến độ đọc phản hồi mặc định tắt; log lỗi và hoàn tất vẫn được giữ. Đặt biến môi trường `BRIDGE_DEBUG=true` trước khi chạy script VPS nếu cần chẩn đoán.

Script VPS không tự đọc `.env`; script local đọc `CHROME_HEADLESS` và `BRIDGE_DEBUG` từ `.env`. Local dùng `CHROME_HEADLESS=false` để tiện đăng nhập, có thể đổi sang `true` sau đó. Hai biến chỉ nhận `true` hoặc `false`; giá trị không hợp lệ làm bridge báo lỗi khi khởi động. Khi Chrome bị ngắt, yêu cầu tiếp theo kết nối lại; yêu cầu đang chạy có thể lỗi và người học dùng thao tác thử lại hiện có.

Tab đăng nhập/trang chủ không thuộc giới hạn tab hội thoại được quản lý. Bridge giữ hàng đợi một yêu cầu AI, thời gian chờ nhập prompt và kiểm tra phản hồi hiện có. CDP/API chỉ dùng trên máy chủ qua địa chỉ loopback. Bản tối ưu đã được triển khai và kiểm tra thực tế trên VPS ngày 01/10/2026; chưa đo hiệu năng bằng benchmark.

### Bản triển khai đã kiểm tra

- Triển khai ngày 02/10/2026; JAR SHA-256: `17F78C0CDBF5634255E5E6B3013AE6F80174C3952ED3FED45AAA38D285A13E82`.
- 137 kiểm thử Java chạy, 136 qua và 1 benchmark chủ động bỏ qua; 65/65 kiểm thử JavaScript qua.
- PostgreSQL 17.11 giữ nguyên 7 tài khoản, 2 phiên hội thoại và 9 bản ghi âm. Sao lưu đã kiểm chứng trước cập nhật: `dictalab-20261002-150009.dump` (4.843.894 byte).
- Dịch vụ và public health đều `UP`; `/conversation` cùng file JS mới trả thành công; API hội thoại từ chối truy cập chưa đăng nhập bằng HTTP 401.
- Bridge ChatGPT khỏe sau cập nhật và bản bridge cũ được giữ tại `C:\DictaLab\backups\coach-bridge-20261002-150015`. JAR trước đó được giữ tại `C:\DictaLab\app\dictalab.previous.jar`.
- `gemini-2.5-flash` được dùng theo mặc định cấu hình `DICTALAB_CONVERSATION_MODEL`; `SHOPAIKEY_MODEL=claude-sonnet-4-5` của Listening Comprehension không đổi. Chưa kiểm tra trọn bốn nhiệm vụ bằng tài khoản người học trên VPS để tránh ghi dữ liệu thử vào production.
- Tác vụ bridge chạy liên tục (`ExecutionTimeLimit=PT0S`), giữ cơ chế thử khởi động lại hiện có và dùng tài khoản Administrator đang đăng nhập. Tác vụ vẫn khởi động theo đăng nhập; đăng xuất tài khoản hoặc reboot cần đăng nhập lại để tác vụ chạy.

## Chạy local

1. Thêm vào `.env` (đã được Git bỏ qua):

   ```dotenv
   DICTALAB_CONVERSATION_ENABLED=true
   DICTALAB_CONVERSATION_MODEL=gemini-2.5-flash
   SHOPAIKEY_API_KEY=<khóa ShopAIKey của máy chủ>
   CARTESIA_API_KEY=<khóa Cartesia của máy chủ>
   CARTESIA_VOICE=db6b0ed5-d5d3-463d-ae85-518a07d3c2b4
   CARTESIA_MODEL=sonic-3.6
   CARTESIA_VERSION=2026-08-14
   ```

2. Chạy `pwsh -File scripts/run-local.ps1 -WithCoach`. Chrome của bridge cần đăng nhập ChatGPT.
3. Mở `http://localhost:18081/conversation` và đăng nhập DictaLab.

Khóa không được gửi xuống trình duyệt hoặc đưa vào prompt ChatGPT. ShopAIKey và Cartesia gọi từ backend; Cartesia theo [TTS Bytes API](https://docs.cartesia.ai/api-reference/tts/bytes). ChatGPT dùng bridge và compatibility patch hiện có; Coach cùng bước chấm/làm rõ hội thoại chia sẻ giới hạn một yêu cầu Web đồng thời. Khi bridge bận hoặc nhà cung cấp lỗi, giao diện giữ phiên để thử lại.

## Dữ liệu và kiểm chứng

Migration V22 thêm hai bảng, không sửa dữ liệu Dictation/Listening. Phiên và audio riêng tư gắn với chủ sở hữu; xóa tài khoản xóa cả hai qua khóa ngoại. API giữ CSRF và trả `Cache-Control: no-store`.

Backend kiểm tra JSON, ID và evidence; AI quyết định ý nghĩa, backend quyết định tiến trình và tính số ý hiểu. Không cộng điểm từ evidence không xuất hiện trong câu trả lời.

Chạy kiểm thử:

```text
mvn test
node --test src/test/js/*.test.cjs
```

Đo hiệu quả học bằng ý hiểu không mở hỗ trợ và kết quả câu mới cuối phiên. Đây là bằng chứng trong phiên, chưa đủ để kết luận tiến bộ dài hạn. V1.5 sẽ bổ sung cá nhân hóa, lịch ôn, Error Bank và so sánh các phiên tương đương; chưa đưa vào V1.
