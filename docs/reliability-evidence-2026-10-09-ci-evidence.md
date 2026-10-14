# DictaLab — giữ bằng chứng concurrency trong CI, 09/10/2026

Đã sửa **P1: thất lạc CSV concurrency trên CI** bằng thay đổi nhỏ trong workflow hiện có. JavaScript đạt **121/121** trên snapshot cố định. Chưa push, dispatch CI, deploy, truy cập S3 thật hoặc tắt HP.

## Khoảng trống và thay đổi

`ConcurrencyMatrixTest` mặc định ghi `target/concurrency-matrix.csv`. Job `reliability` trước sửa build vào `target/reliability`, chỉ upload XML và worker/restore logs; CSV nằm ngoài danh sách artifact nên có thể mất khi runner kết thúc. XML vẫn xác minh test outcome, nhưng không thay bằng chứng từng race. Bản trước sửa được giữ trong snapshot của [batch S3 gate](reliability-evidence-2026-10-09-s3-gate.md).

Trong [.github/workflows/deploy.yml](../.github/workflows/deploy.yml):

- Thêm `-Ddictalab.race-evidence=target/reliability/concurrency-matrix.csv` vào lệnh Maven của job reliability.
- Sau verifier XML, yêu cầu `test -s target/reliability/concurrency-matrix.csv`; thiếu/rỗng thì job không được chấp nhận.
- Thêm CSV vào artifact có `if: always()` để giữ bằng chứng khi một step thất bại thông thường. Runner bị mất/hủy đột ngột vẫn có thể không upload; không hứa giữ bằng chứng trong mọi loại crash.
- Giữ nguyên PG17/18 matrix, Java 17, timeout, verifier mandatory, điều kiện deployment và phụ thuộc `needs: [test-build, reliability]`.

Không sửa Java/POM/resources/API, không thêm dependency, không chạy lại backend nặng khi compile input không thay đổi. CSV presence gate không kiểm tra toàn bộ cardinality hoặc thay thế assertions/JUnit verifier; kiểm chứng từng phase vẫn thuộc ma trận và raw evidence.

## Kiểm chứng local

Raw: `target/reliability-ci-evidence-20261009/`.

| Bằng chứng | Kết quả |
|---|---|
| `red.xml` | 3 ca contract, 1 fail trước sửa: thiếu đường dẫn CSV được upload |
| `green.xml` | 3/3 sau sửa |
| `js-final.xml` | 121/121, không fail/skip/cancel, snapshot cố định |
| `workflow-check.log` | YAML parse và mapping gate/artifact/deploy PASS; shell gate 3/3 tình huống PASS |

`CheckWorkflow.java` là diagnostic trong evidence, không thêm vào production/test source. Nó dùng **SnakeYAML 2.6 đang có trong JAR**, SafeConstructor và từ chối duplicate YAML keys; không tải thư viện mới. Đọc workflow thực, xác minh matrix/path/artifact/deploy dependency, rồi chạy đúng block `run` của evidence gate qua Git Bash local với fail-fast giống shell step.

Fixture shell dùng 17 XML PG17 và CSV hợp lệ từ batch trước: verifier báo 166 ca đạt; CSV có dữ liệu cho exit 0, CSV thiếu hoặc rỗng cho exit 1. Mỗi fixture giữ `gate.log` và reports đã dùng. Đây là test command/config với bằng chứng đã lưu, **không phải một lượt Testcontainers hoặc GitHub Actions mới**. YAML parse local cũng không chứng nhận toàn bộ semantics/scheduler của GitHub Actions.

Tiêu chí đạt cho thay đổi này: contract phải đỏ trước sửa/xanh sau sửa; snapshot đủ source; CSV được định tuyến đúng artifact; shell gate không chấp nhận missing/empty; workflow giữ các deployment guards. Đã đạt local. Còn mở: chạy thật trên CI sau khi được phép push/dispatch và kiểm tra artifact tải về có CSV, XML, child logs.

## Source và bàn giao

`ship-source.zip` có **359 file**, SHA256 `8795440B7476204C6B5D286023595CFBD6CA28B14A1C408A31549F790E43BB6A`. So với source tái hiện batch S3 gate chỉ `.github/workflows/deploy.yml` và `src/test/js/reliability-gate.test.cjs` thay đổi; toàn bộ Java/resources/POM giữ nguyên byte-for-byte. Docs bàn giao riêng. Không ghi đè thay đổi UI hoặc gán kết quả snapshot cho source khác.

Gate backend gần nhất vẫn là **17 suite/166 ca mỗi PG17/18**; runtime/JAR/provenance thuộc báo cáo batch S3 gate. Batch này không tạo JAR mới và không gán một kết quả CI remote chưa chạy.

`handoff-bundle.zip` giữ snapshot, diff, red/green/full JS, diagnostic và thư viện có sẵn, shell fixture/logs và báo cáo/runbook; có manifest hash. Gói dùng nội bộ vì XML chứa test credentials/dữ liệu synthetic, cần rà soát bản sao trước khi công bố. Không chứa `.env` thật, production dump hoặc cloud credentials; giữ nguyên lượt lỗi. `target` bị Git ignore, sao lưu trước khi dọn.

Ponytail giới hạn thay đổi ở đường dẫn và gate hiện có; encoding guard kiểm tra UTF-8 tài liệu/workflow/test. Không thêm một hệ thống thu thập bằng chứng mới.

## Bước còn cần thông tin

Để tiếp tục nghiệm thu S3/staging, cần host và DB/Redis/RabbitMQ riêng, bucket/prefix test tách biệt, ngân sách/trần call và phạm vi thao tác được phép. Không gửi mật khẩu/access key trong chat. Không dùng production như staging, không bỏ S3 release gate, không tự tăng tài nguyên hoặc trả phí provider. Chưa đủ điều kiện nghiệm thu toàn bộ nên chưa tắt HP.
