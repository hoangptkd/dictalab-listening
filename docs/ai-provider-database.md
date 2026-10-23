# Tài khoản AI trong PostgreSQL

## Phạm vi

Áp dụng local, chưa deploy production. Java và Python dùng cùng database PostgreSQL
của ứng dụng; không có database tài khoản thứ hai hay API HTTP trả key.
Registry hỗ trợ `gemini`, `deepseek`, `openai_compatible`. Chọn nền tảng tường minh;
không tự chuyển từ Gemini miễn phí sang DeepSeek tính phí.

Hai bảng Flyway V42 (đổi từ V40 khi tích hợp với payment/Coach trên main):

- `ai_provider_pools`: nền tảng, model mặc định, trần global RPM/RPD và trạng thái.
- `ai_provider_accounts`: tài khoản/project, model, endpoint, quota, priority, trạng thái
  và API key mã hóa AES-256-GCM. Ciphertext gắn với ID/nền tảng/tài khoản/model/endpoint;
  không sửa trực tiếp các trường này bằng SQL, hãy dùng CLI để mã hóa lại.

Tên lớp/module Gemini cũ được giữ để tương thích. Redis Gemini giữ nguyên
`{dictalab-gemini-quota}:v2:`; nền tảng khác dùng `{dictalab-ai-quota}:v2:<platform>:`.
Quota global của từng nền tảng độc lập, Java/Python cùng protocol Lua.
RPD nội bộ reset lúc 00:00 Pacific. Memory backend chỉ dành cho test, không phối hợp nhiều process.

## Cấu hình máy chủ

Chỉ giữ kết nối và khóa gốc ở cấu hình được bảo vệ:

```dotenv
AI_PROVIDER_CONFIG_SOURCE=database
AI_PROVIDER_DATABASE_URL=postgresql://<user>:<url-encoded-password>@127.0.0.1:15433/<database>
AI_PROVIDER_CONTAINER_DATABASE_URL=postgresql://<user>:<url-encoded-password>@postgres:5432/<database>
AI_PROVIDER_ENCRYPTION_KEY=<32-random-bytes-in-standard-base64>
GEMINI_QUOTA_BACKEND=redis
GEMINI_QUOTA_REDIS_URL=redis://127.0.0.1:16379/0
```

Java dùng datasource ứng dụng hiện có, không dùng DSN Python. Trong Compose, Python
nhận DSN container; Redis dùng địa chỉ `redis:6379`, Java host dùng `127.0.0.1:16379`.
DB từ xa cần `sslmode=verify-full` và CA tin cậy; ngoại lệ `postgres` chỉ dành cho
network Compose local riêng của repository, không dùng ngoại lệ này cho máy chủ từ xa.

Giữ master key ngoài DB và sao lưu riêng trong nơi bảo vệ. Mất key thì không giải mã
được API key. Backup DB sau import chứa ciphertext, nhưng vẫn có dữ liệu ứng dụng riêng tư.
Không Git, không log, không đưa key/DSN vào tham số process hoặc báo cáo.

## Import và quản lý

1. Dừng/drain consumer trước khi đổi cấu hình; sao lưu DB bằng `pg_dump -Fc`, kiểm tra
   bằng `pg_restore --list` (và restore thử khi chuẩn bị release).
2. Áp dụng migration bằng Flyway thông thường. Không tự tạo bảng ngoài Flyway.
3. Khi còn cấu hình Gemini env, chạy `python tools/ai-eval/provider_accounts.py import-env`
   với JSON bổ sung qua stdin (`{"pools":[],"accounts":[]}` nếu chỉ nhập Gemini).
   CLI nhập atomically, xác minh giải mã, tạo backup env mã hóa rồi chuyển source sang
   database và bỏ các biến account/key Gemini cũ. File `.env` phải có ACL riêng tư trước
   khi chạy. Master key được giữ lại nếu import lỗi; config cũ không bị prune khi chưa xác minh.
4. Xem metadata không có key: `python tools/ai-eval/provider_accounts.py list`.
5. Thêm/sửa bằng `python tools/ai-eval/provider_accounts.py upsert`, JSON qua stdin:

```json
{
  "pools": [{"platform":"deepseek","default_model":"deepseek-flash",
    "global_rpm_limit":13,"global_rpd_limit":100,"enabled":true}],
  "accounts": [{"id":"deepseek-a","platform":"deepseek","projectId":"deepseek-account-a",
    "apiKey":"<supply-secret-through-stdin>","model":"deepseek-flash",
    "url":"https://api.deepseek.com/chat/completions","rpmLimit":13,"operatingRpm":13,
    "tpmLimit":0,"rpdLimit":100,"enabled":true,"priority":0}]
}
```

Không nhập key thật vào câu lệnh shell có history; dùng secret manager hoặc chương trình
truyền stdin. Dùng cùng `projectId` chuẩn cho mọi key của một tài khoản thực; không tạo
nhãn khác nhau để đếm một tài khoản hai lần. Unique `(platform,account_id,model)` ngăn
khai báo lặp. Khi thêm tài khoản, điều chỉnh global cap chủ động nếu muốn tăng tổng.

6. Khởi động lại toàn bộ Java/API/worker/batch để lấy snapshot mới. Java nạp lười ở lần
   dùng registry đầu tiên; Python nạp khi tạo Settings. Không hot reload, không cache DB
   fallback. DB/master/ciphertext lỗi thì ngừng cloud request, không dùng lại env key.

Các script maintenance cũ chỉ đọc `GEMINI_API_KEY` không được tự động cấp key lại;
dùng batch runner/teacher đã nối registry. Cartesia và khóa ngoài text provider không
nằm trong migration này.

## Chọn nền tảng và chạy batch

Batch runner tự nạp `.env` local; environment process đã có sẽ được ưu tiên:

```powershell
python tools/ai-eval/gemini_batch.py --provider deepseek --input data/jobs.jsonl `
  --ledger data/deepseek-batch.sqlite3 --concurrency 1 --max-calls 10 --max-seconds 300
```

Dùng ledger riêng cho mỗi nền tảng. Không replay `UNKNOWN`, timeout hay lỗi sau gửi.
Fingerprint Gemini cũ được giữ khi chuyển env sang DB. Teacher collector vẫn dùng
model Gemini/cap/kiểm duyệt đã định, không tự thay bằng DeepSeek.

Python API/worker: `DICTALAB_AI_PRIMARY_PROVIDER=deepseek`.
Java text client: `DICTALAB_AI_TEXT_PROVIDER=deepseek` (không đổi route production trong task này).
Đối với conversation có model override, phải cấu hình model tương ứng trong account;
không âm thầm đổi model khi request một model khác.

Kiểm tra không sinh dữ liệu:
`python tools/ai-eval/provider_accounts.py probe --platform deepseek`.
Thêm `--generate` để gửi đúng tối đa một request JSON, output tối đa 32 token, không retry;
DeepSeek có thể tính phí request này. `probe --platform gemini` kiểm tra từng nguồn.

## DeepSeek: hợp đồng và giới hạn

Ngày 2026-10-22, tài liệu công bố `deepseek-flash`, endpoint
`https://api.deepseek.com/chat/completions`, JSON object mode. Gateway gửi schema trong
prompt và validate tại local; không giả định strict JSON-schema như Gemini. Tắt thinking
bằng `{"thinking":{"type":"disabled"}}` cho tác vụ này.

DeepSeek tính phí theo token và công bố concurrency theo tài khoản, không phải RPD miễn phí.
13 RPM/100 RPD trong registry là trần vận hành nội bộ ban đầu, không phải quota DeepSeek
hay giới hạn chi tiêu theo USD. Output/input token và các client ngoài hệ thống vẫn ảnh hưởng
chi phí; không tự nạp tiền hoặc chạy batch trả phí nền khi chưa được yêu cầu.

### Local: bỏ trần vận hành (2026-10-23)

Chủ dự án yêu cầu bỏ các trần nội bộ trong giai đoạn phát triển. `.env` local và
`.env.example` đặt `DICTALAB_OPERATIONAL_LIMITS_ENABLED=false`,
`AI_BUDGET_ENABLED=false`, `RATE_LIMIT_ENABLED=false`, `AI_CONCURRENCY=0`,
`TTS_CONCURRENCY=0`. Compose chuyển cờ vận hành vào cả AI service và worker;
Java nhận cấu hình qua launcher đang có. Khi chạy Python trực tiếp phải nạp env;
runner teacher đã đọc cờ này từ `.env`.

- Provider vẫn chọn account/model từ registry; không tắt routing để bỏ quota.
  Các trần RPM/TPM/RPD của account và pool không chặn admission khi cờ là `false`.
  Redis/memory vẫn ghi usage; HTTP 429 và cooldown thực tế của provider vẫn áp dụng.
- Báo cáo quota giữ `configuredRpdLimit`/`configuredGlobalRpdLimit`, nhưng trần
  có hiệu lực là `0` và `availableCalls=null` nghĩa là không có trần nội bộ.
  Đây không phải bằng chứng tài khoản còn quota từ nhà cung cấp.
- Bộ thu thập bỏ trần 1.500 ứng viên trong chế độ này, không sửa manifest cũ.
  Báo cáo giữ `configuredCandidateCap`; `candidateCap=0` nghĩa là không giới hạn.
  Bỏ trần batch 20 ứng viên và trần cấu hình 10 vòng; `max_rounds=0` cũng cho phép
  không giới hạn khi bật lại cờ. Mục tiêu dataset và duyệt độc lập vẫn giữ nguyên.
- Bỏ trần daily budget cho cloud và local; công tắc dừng khẩn cấp vẫn có hiệu lực.
  Tắt HTTP rate limiter local bằng cấu hình hiện có, giữ xác thực và CSRF.
- AI service hỗ trợ concurrency/HTTP connections bằng `0` (không giới hạn);
  cờ `false` cũng bỏ cả hai trần này. Java provider hỗ trợ concurrency `0`;
  các client conversation/coach không còn trần cố định một request.
  Worker audio/coach giữ số worker cấu hình nhưng không còn trần 8/4 worker.
- Batch eval mặc định `--max-calls 0 --max-seconds 0`; vẫn hỗ trợ trần tường minh
  nếu người chạy truyền số dương. Bỏ trần concurrency 5 của batch.
- SQLite job admission bỏ trần 1.000 job khi cờ là `false`. Compose bỏ memory
  limit của Redis/RabbitMQ và đặt Redis `maxmemory=0`; giữ persistence/noeviction.

Giữ các ràng buộc schema/Unicode, rubric, dedup/leakage, idempotency, bảo vệ dữ liệu,
request deadline, circuit sau lỗi và cấu hình queue RabbitMQ hiện hữu. Đổi queue
durable tại chỗ có thể gây lỗi khai báo; việc này cần migration riêng để không mất job.
Các benchmark lịch sử giữ kích thước mẫu đã khóa, không biến thành batch vô hạn.
Giới hạn thật từ API, context của model và tài nguyên máy không thể gỡ bằng cờ local.

Thay đổi áp dụng khi tiến trình nạp lại cấu hình/code; container cần rebuild/recreate.
Task này không restart dịch vụ đang chạy, không sinh dữ liệu, không train và không tắt máy.

Nguồn: [Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/),
[JSON Output](https://api-docs.deepseek.com/guides/json_mode/),
[Rate Limit & Isolation](https://api-docs.deepseek.com/quick_start/rate_limit/).

## Khôi phục

Dừng consumer, khôi phục backup DB vào môi trường kiểm tra trước. Backup env dùng envelope
`v1:` như account; AAD có `id=env-backup`, `platform=gemini`, `account_id=local`,
`model=backup`, `endpoint=local`, dùng cùng master. Khôi phục cấu hình chỉ vào file bảo vệ,
không in plaintext ra terminal. Không đổi lại `env` khi thiếu bộ cấu hình cũ đã xác minh.
Khôi phục toàn DB cần cửa sổ bảo trì và kiểm tra dữ liệu phát sinh sau backup; không tự động
down migration hoặc xóa bảng registry.
