# Chạy local thống nhất

## Nguồn và địa chỉ

- Checkout duy nhất: `C:/Users/HP/Desktop/English_dictition`.
- Lệnh: `pwsh -File .\scripts\run-local.ps1`.
- Website: <http://localhost:18082>; AI nội bộ: `127.0.0.1:8094`; health Core: `127.0.0.1:18184/actuator/health`.
- Profile: `local`; database: `dictalab_personalized_local_20261102`.
- Tài khoản kiểm thử: `Scenario Local QA`, ID 14. Không tự tạo tài khoản, cấp quyền ADMIN hoặc đăng xuất phiên của người khác.

Wrapper PowerShell luôn chuyển đến checkout trên, kể cả được gọi từ worktree cũ.
Python từ chối chạy chế độ `local` ở nguồn khác. Không sửa `.env` trong checkout để đổi bản local.

## Cấu hình và dữ liệu riêng

Thư mục `C:/Users/HP/.dictalab/local/` nằm ngoài Git và ngoài worktree:

| Thành phần | Vị trí |
| --- | --- |
| Core / AI configuration | `core.env`, `ai.env` |
| QA credential | `qa.json` |
| Studio snapshots / judgments | `studio/` |
| Python evaluation evidence | `python-records/` |
| Durable jobs | `ai-jobs.sqlite3` |
| Build / service logs | `build.log`, `logs/` |
| Source / JAR / prompt / PID receipt | `runtime.json` |

Không commit, chia sẻ hoặc đưa các file cấu hình/credential vào prompt hay báo cáo.
Core không nhận khóa provider; AI worker không nhận credential quản trị Gemini.
Langfuse dùng cấu hình riêng của AI. Giữ service tokens, CSRF, ownership, validation và emergency stop.
Ngoại lệ QA14 chỉ áp dụng local; không bỏ quota của provider.

## Khởi động và dừng

1. PostgreSQL, Redis và RabbitMQ hiện có phải sẵn sàng. Không tạo lại database/volume.
2. Nếu cần mở Docker Desktop, dùng Explorer theo `AGENTS.md`; chờ `docker info` thành công.
3. Chạy lệnh trên. Launcher kiểm tra nguồn, cấu hình, QA identity, schema, pin prompt và cổng; build JAR từ nguồn hiện tại rồi chạy Core, AI API và một AI worker.
4. Chỉ báo sẵn sàng sau khi health Core và AI đạt, worker còn sống. Launcher không tự thay cổng hoặc dùng lại một process không rõ nguồn.
5. `Ctrl+C` dừng các process do launcher tạo. Hạ tầng và dữ liệu được giữ.

`pwsh -File .\scripts\run-local.ps1 -CheckOnly` kiểm tra khởi động rồi dừng các process vừa tạo.
Nếu cổng bận, xác minh command line/PID trước khi dừng; không kết thúc mọi process Java/Python.
Không chạy song song các script launch có ngày tháng trong `data/`.

Core dùng credential runtime không có DDL. Migration phải chạy riêng bằng owner qua quy trình hiện có,
sau backup; launcher từ chối nếu version schema không khớp nguồn. Không bật Flyway bằng credential runtime,
không sửa checksum hay đánh số lại migration đã áp dụng. Migration mới của vocabulary là **V53**; V52 là Java migration scenario đã tồn tại.

## Phạm vi

Local hiện dùng Conversation AI V4.13 cho QA14 qua cơ chế preview có pin. Đây không phải phê duyệt production.
Progress đang dùng read path legacy trong cấu hình local này. Model Serving/SFT không được launcher tự khởi động.
Các lệnh `run-service.py stack` trong [hướng dẫn microservice](microservices-demo.md) dành cho kiểm thử topology riêng,
không thay thế địa chỉ phát triển chính.

## Kiểm tra sau thay đổi

```powershell
python -m pytest -q tools/ai-eval/test_service_launcher.py
pwsh -File scripts/test-run-local-env.ps1
node --test src/test/js/ai-evaluation.test.cjs
```

Kiểm tra `runtime.json` và catalog đã xác thực để xác nhận đúng commit, nguồn và pin;
không lấy nội dung UI đang cache làm bằng chứng runtime mới. Kết thúc phiên QA tự tạo bằng logout.
