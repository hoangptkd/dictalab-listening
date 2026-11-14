# DictaLab — bản đồ hệ thống hiện hành

Cập nhật source: 14/11/2026. Đây là mô tả repository sau gộp; không khẳng định mọi thay đổi đã deploy production.

## Điểm vào duy nhất

- Phát triển local: [hướng dẫn local](local-development.md), `C:/Users/HP/Desktop/English_dictition`, <http://localhost:18082>.
- Conversation AI và prompt: [luồng hiện hành](conversation-ai.md).
- Thí nghiệm chất lượng: [Evaluation Studio](ai-evaluation-studio.md).
- Báo cáo lịch sử và phục hồi: [chỉ mục](project-history.md).

## Các thành phần

| Thành phần | Source | Trách nhiệm |
| --- | --- | --- |
| Core | `src/main/java/com/dictalab` | Auth, quyền dữ liệu, bài học, chấm/tổng hợp, billing, quản trị |
| Web | `src/main/resources/static` | HTML/CSS/JavaScript, dictation/listening/conversation/vocabulary/admin |
| AI API + worker | `services/ai-generation` | Registry provider, gateway, fallback, jobs, prompts, TTS, tracing |
| Progress | `services/progress` | Projection sự kiện học, DB riêng, dashboard theo cấu hình rollout |
| Model Serving | `services/model-serving` | Inference riêng; không tự chạy trong local launcher chính |
| Offline training | `training` | Chuẩn bị/kiểm tra/train/đánh giá có bằng chứng, tách khỏi luồng web |

PostgreSQL giữ trạng thái nghiệp vụ; Redis giữ phiên JWT/điều phối theo cấu hình;
RabbitMQ vận chuyển công việc qua durable ledger/outbox. Không đồng nhất ACK broker với hoàn tất giao dịch AI.
Core không đọc private AI registry. Progress không đọc trực tiếp bảng Core.

Java source target 17; máy phát triển có thể dùng JDK tương thích mới hơn. Spring Boot 4.1.1,
Spring MVC/Security/JPA, Flyway; Python/FastAPI; frontend không thêm framework mới trong cleanup.
Dependencies chuẩn nằm trong `pom.xml` và requirements/lockfiles của từng service.

## Các luồng người học

- Dictation: nghe/nhập từ, feedback, gợi ý/reveal, first-attempt evidence và kết quả.
- Listening comprehension: nghe, trả lời, chấm/ôn; lưu bằng chứng và hỗ trợ hồi phục qua jobs.
- Conversation AI: cá nhân hóa CEFR/TOEIC/IELTS, nhập tình huống, xác nhận spec, sinh ba activity,
  nghe phân vai/liền hoạt động, trả lời Việt/Anh, chấm ý, chữa bài, hoàn thành và gợi ý luyện tiếp.
- Vocabulary: catalog/enrollment, metadata/tags, recall/SRS/practice/quiz/context và mastery.
- Phòng 1–1: signaling WebSocket và audio WebRTC; kiểm tra REST chưa đủ kiểm chứng media hai thiết bị.

Các feature flags/approval pins quyết định chức năng thực sự mở ở từng môi trường.
Không lấy tính năng có source hoặc UI để kết luận đã bật trên production.

## Dữ liệu và tương thích

Resources và Java dùng một dãy Flyway chung. V52 là call-envelope scenario;
V53 mở rộng vocabulary enrollment/tags/level. Không sửa checksum migration đã áp dụng.
Owner chạy DDL; runtime chỉ nhận quyền nghiệp vụ. [Runbook database](aurora-production-databases.md).

Scenario lưu draft/spec, program/profile, workflow pin, session/activity, answer/assessment, operation,
budget/provenance và media. Bảng Conversation legacy giữ cho bài cũ.
Prompt hiện hành V4.13 nằm trong một JSON; compiled archive giữ hash/schema cũ để chấm đúng phiên đã lưu.

Thông tin private và receipt local ở `.dictalab/local` ngoài repository. Benchmark/train/model
không phải cache được phép xóa tùy ý. [Training Studio](training-studio.md), [full07 backup](full07-training-verification-backup-20261026.md).

## Vận hành và bảo vệ

- JWT cookies/Redis, CSRF, ownership và role checks giữ nguyên.
- Không đưa provider key vào browser, Core, Git, prompt hay process arguments.
- Fallback giữ chính sách phân loại lỗi server/tài khoản; UNKNOWN không tự replay.
- TTS/AI có idempotency, timeout và recovery; không giảm validation để che lỗi.
- QA14 có ngoại lệ local; không cấp ADMIN hoặc tắt bảo mật để test.
- Langfuse giữ tracing/redaction hiện có; trace không phải bằng chứng duy nhất về chất lượng.

## Tài liệu chuyên biệt

- [AI operations](ai-platform-runbook.md), [provider registry](ai-provider-database.md)
- [Microservice scope](microservices-implementation.md), [topology/demo](microservices-demo.md)
- [JWT/Redis](jwt-redis-auth.md), [queues](redis-rabbitmq.md), [S3](s3-audio.md)
- [Vocabulary](vocabulary.md), [payments](payment-operations.md), [Premium](payos-premium.md)
- [CI policy](ci-policy.md), [operations](operations-runbook.md), [EC2](ec2-production.md)

Code tests, thử provider thật, benchmark, push GitHub và deployment là các kết quả riêng.
Không suy ra chất lượng AI từ số tests hoặc tỷ lệ Harness chấp nhận.
