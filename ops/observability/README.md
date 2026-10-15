# Giám sát DictaLab

Cấu hình version-control; không chứa credential. Management chỉ loopback, cổng 18183 production / 18283 capacity. Nếu chạy Prometheus trên HP, dùng SSH tunnel tới management; **không** mở port Actuator bằng firewall/public proxy.

Cập nhật 03/10/2026: service `DictaLabPrometheus` đang chạy trên VPS, tự khởi động, tài khoản LocalService,
bind **127.0.0.1:19190**, cấu hình/data tại `C:/DictaLab/monitoring`. Cổng 19090 trên VPS thuộc WebLens,
không dùng hoặc dừng process đó. Scrape UP và 7 rules health OK đã kiểm tra; Grafana service/receiver chưa cài.
Có thể forward một cổng HP còn trống tới VPS 19190 để xem Prometheus; không mở public listener.

Chạy Prometheus với `--web.listen-address=127.0.0.1:19090 --config.file=prometheus.yml --storage.tsdb.retention.time=3d --storage.tsdb.retention.size=256MB`. Đặt thư mục dữ liệu riêng ngoài repo. Kiểm tra bằng `promtool check config prometheus.yml`, `promtool check rules alerts.yml`, `promtool test rules alerts.test.yml`.

Grafana datasource dùng Prometheus loopback 19090. Copy thư mục provisioning, đặt `DICTALAB_GRAFANA_DASHBOARDS` là đường dẫn tuyệt đối đến dashboards, bind Grafana loopback và dùng mật khẩu riêng từ environment. Không bật anonymous access. Các panel provider latency chỉ có dữ liệu sau khi provider được gọi; không coi panel trống trước gọi là provider khỏe.

Các threshold là mục tiêu đề xuất: core HTTP p95 <1s, errors <1%; AI/TTS/grade có budget riêng. Điều chỉnh theo capacity report, không mở rộng ngưỡng để làm bài test pass.

Alert rule có runbook action và test firing/resolved cho cả 7 rules. SlowRequests chỉ xét core, loại route AI có budget khác. **Chưa có nơi nhận cảnh báo**: người dùng chốt sau, giữ cấu hình; không tuyên bố email/Telegram đã nhận được alert. Prometheus service đã được cài theo cập nhật trên; Grafana chưa là service thường trực. Lịch scrape là giám sát vận hành có mục đích, khác với job thử tải phải có thời hạn.

Đã chạy Grafana OSS 13.2.3 và Prometheus thật bằng `scripts/test-observability.ps1`: import 12 panels, datasource OK, mọi query hợp lệ và gauge bắt buộc có dữ liệu. Raw result: `target/readiness/observability-3b2fef3dceef402ea5d9c0c23cfd2804/result.json`. Panel 5xx/provider latency trống khi chưa có sự kiện tương ứng không được coi là dữ liệu failure. Tiến trình tạm và tunnel đã dừng sau kiểm tra. Hướng dẫn xử lý: `docs/operations-runbook.md`.

Tracing dùng starter chính thức của Spring Boot, sampling 10%, export tắt mặc định. Bật `TRACE_EXPORT_ENABLED=true` và `TRACE_ENDPOINT` chỉ khi collector sẵn sàng. Queue export tối đa 256 spans, batch 64, request timeout 2s; không xuất answer/prompt/token/cookie. OTLP metrics và log export tắt, dùng Prometheus pull và JSON app log rotation 10 MB/file, total cap 100 MB, history 7 ngày.

Nguồn: [Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html), [Prometheus alert rules](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/).
