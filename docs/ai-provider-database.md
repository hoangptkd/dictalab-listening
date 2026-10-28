# Tài khoản AI trong PostgreSQL

## Phạm vi

Áp dụng local, chưa deploy production. Có hai cấu hình: `legacy` giữ registry
trong database Core để rollback; `private` chuyển registry sang database do AI sở hữu.
Runtime mới của Core chỉ gọi HTTP có xác thực, không nhận master key hay provider key.
Không có API HTTP trả key. Phần cấu hình chung bên dưới mô tả luồng legacy;
luồng private có quy trình riêng ở cuối tài liệu.
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

Đây là chế độ legacy. Không cấp datasource Core cho runtime AI trong chế độ private.

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

## Registry riêng cho AI microservice (28/10/2026)

### Chuẩn bị và sao chép

1. Backup Core bằng `pg_dump -Fc`, kiểm tra danh sách archive và thử restore vào
   database tạm mới. Ghi nhận parity trước khi đổi runtime. Không ghi đè Core.
2. Quản trị PostgreSQL tạo database riêng, ví dụ `dictalab_ai`, owner riêng
   không superuser/createdb/createrole/replication/bypassrls hoặc membership
   có quyền cao. Runtime dùng role khác, không có bất kỳ role membership nào.
   Thu hồi PUBLIC CONNECT/CREATE và chỉ cấp quyền cần thiết. Không dùng role
   quản trị PostgreSQL để chạy service.
3. Tạo file quản trị bảo vệ `data/microservices/ai-registry-owner.env` với
   `AI_PROVIDER_DATABASE_URL` của owner và `AI_PROVIDER_ENCRYPTION_KEY` mới,
   khác master legacy. File runtime `ai.env` dùng DSN read-only và cùng master
   AI mới. Giữ DSN/key trong file có ACL hạn chế, không truyền qua đối số CLI.
4. Khởi tạo schema và copy bằng các lệnh từ gốc repository:

   ```powershell
   python tools/ai-eval/provider_accounts.py init-private --env-file data/microservices/ai-registry-owner.env
   python tools/ai-eval/provider_accounts.py copy-registry --env-file .env --target-env data/microservices/ai-registry-owner.env
   ```

   CLI dùng đúng file tường minh, không lấy biến hoặc nội suy credentials từ
   process cha. Init chỉ nhận database rỗng hoặc schema có checksum đúng;
   migration AI độc lập tại `services/ai-generation/app/sql/001_provider_registry.sql`.
   Không chạy lại Flyway Core trong database AI.
5. Copy không đổi source/config. Nó mã hóa lại theo master mới, bảo toàn flags
   account trong pool đang tắt, tạo backup mã hóa có ACL bảo vệ và giải mã kiểm
   chứng trước import. Đích khác dữ liệu bị từ chối; đích giống hệt là no-op.
   Source thay đổi trong khi copy khiến transaction đích rollback. Tạm dừng
   chỉnh registry trong cửa sổ copy/cutover để tránh snapshot lỗi thời.
6. Owner cấp runtime `USAGE` schema và `SELECT` trên ba bảng
   `ai_provider_pools`, `ai_provider_accounts`, `ai_registry_schema`; không cấp
   INSERT/UPDATE/DELETE/TRUNCATE/CREATE hay quyền SET ROLE. Thu hồi quyền
   PUBLIC và không cấp AI đọc bảng Core. Core runtime không được CONNECT DB AI
   hoặc đọc registry legacy; giữ role quản trị migration ngoài runtime.

AI runtime đặt `AI_PROVIDER_CONFIG_SOURCE=database`,
`DICTALAB_AI_REGISTRY_OWNERSHIP=private`, DSN read-only và master riêng.
Startup xác minh schema/checksum, role hạn chế, không membership hoặc quyền ghi
registry. Sai điều kiện thì từ chối cloud request, không lùi về key legacy.
CLI upsert dùng file owner; không cấp owner file cho API/worker.

### Chạy và cutover local

Core dùng `DICTALAB_AI_TEXT_TRANSPORT=python`, model/provider tường minh và các
mode TTS/coach/review đã chọn. File Core không có provider key, registry DSN
hay master. AI và Core chia sẻ internal service token; Model Serving dùng token
riêng. Progress có DB/role riêng. Bật caller budget/rate limit phù hợp ở Core;
ngân sách cloud demo mặc định 0. Không public cổng nội bộ.

```powershell
python scripts/run-service.py core --env-file data/microservices/core.env
python scripts/run-service.py ai --env-file data/microservices/ai.env
python scripts/run-service.py worker --env-file data/microservices/ai.env
python scripts/run-service.py progress --env-file data/microservices/progress.env
```

Build JAR Core/Progress trước. Launcher không tự đọc `.env` chung, không thừa
kế application secrets/proxy/JVM injection từ process cha và kiểm tra biến theo
service. API/worker phải dùng cùng SQLite path tuyệt đối. `run-local.ps1` và
`run-model-demo.ps1` là launcher legacy, không chứng minh cách ly credentials.

Local đã copy 2 pools/4 accounts, kiểm backup/parity, quyền runtime và Core
isolation. Core đã backup/restore kiểm chứng và migrate V41→V43 với checksum
Flyway. Cả ba service + worker đã health PASS, route không xác thực trả 401.
Đây là cấu hình development; chưa có artifact hội thoại production được duyệt.

### Restore và rollback

Backup copy là envelope `v1:`, dùng master AI mới và AAD
`id=registry-copy, platform=gemini, account_id=operator, model=backup, endpoint=local`.
Giải mã chỉ trong bộ nhớ qua `app.provider_registry.decrypt_key`; không in payload.
Tạo database/owner phục hồi riêng, `init-private`, truyền payload trong bộ nhớ vào
`tools/ai-eval/provider_accounts.py` hàm `apply_accounts`, rồi so `snapshot()`.
Smoke dưới đây tự thực hiện quy trình này bằng dữ liệu giả.

Để rollback, drain công việc và khôi phục cả cấu hình legacy bảo vệ đã xác minh
cùng mode legacy; khởi động lại Core trước khi tắt gateway AI. Chỉ đổi flag trên
file Core đã bỏ credentials là chưa đủ. Không xóa registry AI/Core hoặc down
migration. Đối chiếu các cập nhật registry sau copy trước khi quay lại bản cũ.
Root `.env` local giữ cho operator/rollback, không cấp cho runtime mới.

```powershell
python -X utf8 tools/ai-eval/registry_smoke.py
python -X utf8 -m pytest -q tools/ai-eval/test_provider_accounts.py tools/ai-eval/test_service_launcher.py
```

Smoke dùng PostgreSQL Docker riêng, kiểm copy/restore/parity, disabled flags,
checksum, role memberships, runtime read-only, Core isolation và từ chối đích
không an toàn. Không gọi API trả phí. Việc triển khai VPS vẫn là mốc riêng.

## Khôi phục

Dừng consumer, khôi phục backup DB vào môi trường kiểm tra trước. Backup env dùng envelope
`v1:` như account; AAD có `id=env-backup`, `platform=gemini`, `account_id=local`,
`model=backup`, `endpoint=local`, dùng cùng master. Khôi phục cấu hình chỉ vào file bảo vệ,
không in plaintext ra terminal. Không đổi lại `env` khi thiếu bộ cấu hình cũ đã xác minh.
Khôi phục toàn DB cần cửa sổ bảo trì và kiểm tra dữ liệu phát sinh sau backup; không tự động
down migration hoặc xóa bảng registry.
