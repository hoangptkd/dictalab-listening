# DictaLab Training Studio

Giao diện A đã duyệt từ Stitch, tích hợp tại `/admin/training` trong khu vực quản trị. Dùng HTML/CSS/JavaScript hiện có và Spring Security; không thêm frontend framework hay dependency train.

## Chức năng

- Tổng quan nhiều lần chạy: trạng thái, optimizer step, loss, learning rate, validation, thời gian bắt đầu và checkpoint.
- Chi tiết metrics, cấu hình LoRA/train, dataset, log; tìm kiếm/lọc và so sánh tối đa 4 lần chạy.
- Dataset: record theo split, bài theo trình độ, reviewer gốc, audit/holdout/near duplicate, approval và hash.
- Checkpoint: xác minh native `check_resume_checkpoint`, toàn bộ SHA-256 và adapter/optimizer/scheduler/RNG/trainer state.
- CPU/RAM/đĩa là số đo **toàn máy chạy Studio**. GPU, nhiệt độ, điện năng và ETA chưa có bằng chứng sẽ ghi rõ chưa có dữ liệu.
- Tạo nháp từ cấu hình/dataset đã duyệt, preset CPU và tham số nâng cao. Start/resume/pause/stop có xác nhận, CSRF, tài khoản ADMIN và khóa worker độc quyền.
- Thông báo trong dashboard khi trạng thái đổi; polling 15 giây. Khi mất kết nối, hiển thị snapshot cũ và khóa điều khiển.

Trainer hiện được kiểm chứng cho **Qwen3.5-4B**, CPU float32 LoRA, revision được ghim. Danh sách có thể trình bày các model trong metadata, nhưng bộ chạy chưa hỗ trợ khởi tạo model tùy ý, CUDA hay quantization mới.

## Kết nối máy train

Backend Spring và runtime train phải chạy trên cùng máy, cùng checkout. Mặc định bridge **tắt**; web trên máy không có runtime sẽ trả 503 thay vì tạo dữ liệu giả.

Thiết lập trên môi trường chạy backend:

```powershell
$env:DICTALAB_TRAINING_ENABLED = 'true'
$env:DICTALAB_TRAINING_ROOT = 'C:\Users\HP\Desktop\English_dictition'
$env:DICTALAB_TRAINING_PYTHON = 'C:\Users\HP\Desktop\English_dictition\data\fine-tuning\.venv-cpu\Scripts\python.exe'
```

Sau đó khởi động backend theo quy trình hiện có và mở `/admin/training` bằng tài khoản ADMIN. Trên Linux cần chỉ định Python của venv tương ứng. Bật bridge hoặc mở dashboard **không khởi chạy trainer**. Không cấu hình bridge tới máy từ xa; đây không phải dịch vụ điều khiển cluster.

API chỉ nằm trong `/api/admin/training`; quyền ADMIN và CSRF của ứng dụng áp dụng cho cả trang, tài liệu HTML trực tiếp và API. Java chỉ chạy module Python cố định, truyền JSON qua stdin, có giới hạn kích thước/thời gian; trình duyệt không truyền command hay đường dẫn thực thi. Dataset/config/runtime/log/credential không đưa vào Git.

## Điều khiển và khôi phục

1. **Tạo lần train** chỉ tạo YAML và marker nháp có output riêng dưới `data/fine-tuning/studio`. Không sửa campaign hay config cũ.
2. **Start** kiểm tra lại config hash, approval/dataset và environment gate; chỉ nhận nháp có output chưa tồn tại. Người dùng xác nhận quyền thử dưới RAM estimate; data/loss-mask gates vẫn bắt buộc.
3. **Pause/Stop** ghi control request. Trainer hoàn tất optimizer step hiện tại, lưu checkpoint native và hash rồi mới dừng; không kill tiến trình. Trong lúc chờ, CPU vẫn hoạt động. Không đánh giá validation trên phần epoch vừa bị dừng.
4. **Resume** chỉ nhận checkpoint hợp lệ mới nhất trong output của chính run, khớp config/data/review/environment/inspection. Khôi phục adapter, optimizer, scheduler và RNG; không chạy lại từ base. Request và kết quả pause cũ được chuyển sang file `.history`, marker/log mỗi phiên mới được giữ riêng.
5. Khi worker lỗi/gián đoạn, giữ log và state. Không tự retry hay tự restart. PID phải khớp thời điểm tạo và command line; Windows venv launcher và worker con được nhận diện riêng.
6. Hoàn tất chỉ được hiển thị khi marker thành công khớp run-manifest, metrics hữu hạn và SHA-256 của final adapter/config. Best checkpoint vẫn chọn bằng validation cuối epoch. Không dùng test để train hoặc chọn checkpoint.

Các phiên resume dùng chung output/checkpoint của một run. Giữ tối đa 2 checkpoint theo config hiện tại; checkpoint cũ có thể đã được native Trainer xoay vòng. Lịch sử phiên không được tính thành các model độc lập.

## Bằng chứng kiểm tra ngày 25/10/2026

- Python boundary/runner: 50 pass, 1 skip có điều kiện (tiny CPU là opt-in và đã được chạy riêng ở kiểm tra bên dưới).
- Toàn bộ `training/tests`: 340 pass, 1 skip trên Python 3.14 đã có đủ dependency; có warning deprecation từ pytest-asyncio. Bộ 50 test trực tiếp được chạy bằng Python 3.12 trong venv CPU.
- Java: 26 kiểm tra route/security và 2 kiểm tra bridge pass. Bridge đã đọc metadata thật qua Java → Python; không gửi lệnh start/resume đến trainer thật.
- Tiny random Qwen offline trên dữ liệu tổng hợp: pause sau step 1, checkpoint native đầy đủ, không eval partial epoch; resume tới step 2 giữ optimizer state, eval và reload best checkpoint đạt. Không tải hoặc train lại model 4B.
- Trình duyệt kiểm tra layout 1440/1280/375 px, search, compare, tab bàn phím, menu mobile, cấu hình nháp, xác nhận điều khiển, checkpoint/hash, dataset, log, trạng thái trống và mất kết nối. Các lệnh điều khiển UI dùng server fixture riêng trên loopback, không tác động runtime train thật.
- Kiểm tra syntax JavaScript/Python, UTF-8 và `git diff --check` trước delivery.

Venv train thiếu `httpx`/`pydantic-settings` của teacher tests; toàn bộ suite được kiểm bằng Python hệ thống đã có các gói này, không cài thêm vào venv train. Workflow CI được bổ sung các requirements đã ghim của AI service và process-test dependency đã có trong CPU lock; không bỏ test. Kết quả CI từ xa cần được xác minh riêng.

Tại thời điểm triển khai: attempt 03 thật vẫn `PAUSED_BY_USER`, step 1/200, checkpoint-1 được xác minh, không có worker thật. Dataset 500 bài, record 800/96/104, hash `84c243b56dd37ba991585d70158fb19336dfaefca5fc1afa07449514a7b18c53` giữ nguyên. Không deploy model hoặc website trong công việc này.
