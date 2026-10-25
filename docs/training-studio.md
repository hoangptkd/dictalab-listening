# DictaLab Training Studio

Giao diện A đã duyệt từ Stitch, tích hợp tại `/admin/training` trong khu vực quản trị. Dùng HTML/CSS/JavaScript hiện có và Spring Security; không thêm frontend framework hay dependency train.

## Local và Google Colab qua HTTPS · 25/10/2026

Giữ giao diện A đã duyệt. Dashboard có trang **Local & Google Colab**, bộ lọc môi trường và các phiên tách biệt theo nguồn/phase. Smoke 1/1 không phải full SFT 200 bước. GPU/VRAM của Colab và RAM hệ thống được ghi riêng; snapshot mất cập nhật không được suy thành trainer thất bại.

### Máy chủ nhận tiến độ

Backend Java nhận metadata mà không cần Python, model, dataset hoặc GPU trên VPS. Local bridge bên dưới vẫn mặc định tắt và chỉ dùng để điều khiển runtime trên chính máy backend. Hai máy có thể đồng thời gửi tiến độ tới cùng dashboard HTTPS.

Thiết lập trên backend đã triển khai, bằng runtime configuration ngoài Git:

```text
DICTALAB_TRAINING_COLAB_ENABLED=true
DICTALAB_TRAINING_COLAB_DIRECTORY=/var/lib/dictalab/training-telemetry
DICTALAB_TRAINING_COLAB_PUBLIC_URL=https://dictalab.hoangptkdhy.workers.dev
```

Thư mục metadata cần quyền ghi của tài khoản dịch vụ, ở vùng lưu trữ bền vững, không public và được sao lưu. Linux tạo directory/file với quyền 700/600; Windows kế thừa ACL của thư mục runtime riêng. File store này dùng **một JVM writer**; chưa hỗ trợ nhiều replica hoặc store chia sẻ qua network filesystem.

Proxy HTTPS phải chuyển tiếp thông tin scheme theo cấu hình proxy tin cậy hiện có. Không mở origin trực tiếp hoặc mở rộng trusted proxies tùy tiện. Ingestion từ HTTP bị từ chối; reporter giữ TLS verification và không theo redirect.

### Đăng ký và khóa kết nối

1. ADMIN mở `/admin/training?view=sources` → **Kết nối máy train** → chọn Colab hoặc Local.
2. Colab cần URL notebook Drive, không kèm query/fragment. Local để trống URL. Mỗi phiên train/phase có đăng ký riêng.
3. Khóa ngẫu nhiên 256 bit chỉ hiện một lần, có hạn 7 ngày. Backend chỉ lưu SHA-256 của khóa. Không lưu khóa trong notebook, mã nguồn, process arguments, báo cáo hoặc Git.
4. Khóa chỉ được POST metadata cho đúng ID; không đọc dashboard/dataset và không gọi start/resume/pause/stop. ADMIN có thể thu hồi; thu hồi không dừng trainer hoặc xóa bằng chứng.
5. Reporter vẫn dùng GET `/api/auth/csrf` và cookie/header CSRF trước POST `/api/training/telemetry/{id}`. Không bỏ CSRF của ingestion hoặc các API quản trị. Rate limit dùng nhóm AI hiện có.

Snapshot tối đa 32 KB, schema cho phép chỉ các trường metadata đã xác định; không nhận command/path/raw log/dataset text. Sequence phải tăng; retry có cùng bytes/sequence được nhận idempotent. Server kiểm tra timestamp trong 5 phút, số hữu hạn, hash đúng định dạng, step không lùi; bindings config/data/review/environment/inspection/trainer, phase, model, planned steps và cấu hình không được đổi sau snapshot đầu tiên. Thay môi trường/config cần phiên mới.

### Colab

Tải `/admin/colab_reporter.py` bằng phiên ADMIN, đưa file vào Colab. Ô kết nối do dashboard cung cấp dùng `getpass` để hỏi khóa. Ví dụ phần cấu hình (endpoint lấy đúng từ dashboard):

```python
import colab_reporter
reporter = colab_reporter.connect(
    endpoint=endpoint_from_dashboard,
    root="/content/qwen-sft-env04",
    config="/content/qwen-sft-env04/data/fine-tuning/reviewed-500-v14-low-b1-20261024/training-config-colab-04.yaml",
    status_file="/content/sft-t4-full-04-status.json",
    log="/content/sft-t4-full-04.log",
    phase="full",
)
```

Kiểm tra `reporter.state` có `connected=True` trước khi chạy ô train riêng. `reporter.stop()` chỉ dừng gửi tiến độ. Reporter không thực thi source trong backup, không sửa config/trainer, không cài gói, không chạy lại smoke hoặc khởi chạy train. Cần YAML/psutil đã có trong môi trường train; GPU đo bằng `nvidia-smi`, không import torch để đo.

Để gửi bằng chứng smoke 04 có sẵn, đăng ký ID riêng, đổi config sang `training-config-colab-smoke-04.yaml`, status/log sang `sft-t4-smoke-04-status.json` / `sft-t4-smoke-04.log` và `phase="smoke"`. Không rerun smoke. Đồng bộ chạy mỗi 15 giây và chỉ gửi structured metrics; lỗi kết nối không ảnh hưởng trainer. Sau 90 giây không nhận snapshot, dashboard gắn mất cập nhật; trạng thái RUNNING cũ thành chưa xác minh, giữ trạng thái được máy gửi báo cáo.

Smoke đạt cần manifest/config/hash adapter khớp trên máy gửi, optimizer update thật, tensor adapter thay đổi và gradient hữu hạn; exit 0 không đủ. Full được máy gửi báo hoàn tất cần đủ planned steps, validation loss hữu hạn, không truncate, đúng mask và manifest trainingReady. Máy chủ vẫn ghi rõ **báo cáo từ máy gửi**, không xem metadata là file checkpoint đã xác minh độc lập. `/content` là vùng tạm; notebook lưu trên Drive không tự sao lưu checkpoint/model.

### Máy local gửi tới cùng website

Đăng ký nguồn **Máy local · CPU**, lấy endpoint và chạy trong checkout đã cập nhật bằng venv CPU hiện có:

```powershell
& 'data/fine-tuning/.venv-cpu/Scripts/python.exe' -m training.studio_publisher --list
& 'data/fine-tuning/.venv-cpu/Scripts/python.exe' -m training.studio_publisher --endpoint $endpointFromDashboard --run-id $localRunId
```

Publisher hỏi khóa bằng `getpass`. `--once` gửi một snapshot; mặc định gửi mỗi 15 giây. Ctrl+C chỉ dừng publisher. Dùng `studio.describe` và native checkpoint gate hiện có, không gửi raw log/path/dataset. Không start/resume/pause trainer và không đổi marker/config/dữ liệu. Các phiên HTTPS chỉ có quyền theo dõi; muốn điều khiển CPU phải vào backend đang truy cập runtime local.

Không tự chuyển checkpoint CPU float32 ↔ CUDA FP16. Model/dataset giống nhau chưa đủ điều kiện resume: config, environment và source trainer đang khác. GPU checkpoint được máy gửi kiểm hash vẫn có `verified=false` trên máy chủ khi chưa truyền file/state để xác minh native.

### Bằng chứng tích hợp

Bản sao lưu Colab `qwen35-sft-evidence-20261024T195246Z.zip` được đọc tại máy local, kiểm SHA-256 `4041993305ad44d24fb598d8a7f8f648dd51ce6f3ea3228cec71d679288bab87`, inventory 119 file đều khớp; kiểm path/duplicate/symlink/giới hạn dung lượng trước khi đọc. Không thực thi mã trong ZIP. Smoke 01 lỗi runtime, smoke 02 OOM, smoke 03 gradient NaN không đạt, smoke 04 có 1 optimizer update / 32 adapter tensors đổi / gradient hữu hạn. Đây là bằng chứng của snapshot cũ, không phải kết nối Colab hiện tại.

Kiểm tra có thể chạy lại, không chạy model 4B:

```text
python -m pytest training/tests -q
mvn -Dtest=ColabTelemetryTest,TrainingStudioTest,SpaRoutingTest,ColabCsrfProtocolTest,RequestRateLimitFilterTest test
node --check src/main/resources/static/js/training.js
```

Các bài kiểm tra gồm quyền ADMIN/CSRF thật, scope/expiry/revoke của khóa, HTTPS bắt buộc, replay/đổi bindings, NaN/thiếu update/adapter hỏng, phân biệt thanh tải weights với optimizer steps, snapshot cũ, cùng dashboard có nguồn CPU và GPU, publisher không gọi trainer. Xem thử UI dùng server fixture loopback với metadata thật đã lọc; thao tác đăng ký/thu hồi là fixture. Website production và kết nối tới notebook thật cần triển khai backend và đăng ký nguồn riêng; không được coi preview là đã đồng bộ trực tiếp.

## Chức năng local

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
