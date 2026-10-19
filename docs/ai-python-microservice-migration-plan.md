# Kế hoạch chuyển AI sang Python và tách microservice

Mục tiêu là tách phần tạo nội dung AI ra khỏi Spring Boot modular monolith để có thể mở rộng model, LangChain, LangGraph và DSPy mà không làm gián đoạn phiên luyện hiện tại. Giai đoạn đầu chỉ tách `conversation generation`; Java vẫn là hệ thống sở hữu người dùng, phiên luyện, lịch sử và kết quả. Việc chuyển lưu lượng dùng Strangler Fig, bắt đầu bằng shadow mode rồi mới canary và chuyển hẳn.

## Scope

- In: dịch vụ Python cho tạo câu nghe hội thoại, hợp đồng API, gọi Ollama/Qwen, workflow nhiều bước, tối ưu prompt offline bằng DSPy, quan sát, bảo mật nội bộ, kiểm thử và rollout.
- Out: chưa tách toàn bộ tài khoản, bài học, PostgreSQL, frontend, TTS hoặc judge production; chưa đưa DSPy chạy trong request của người học; chưa cho dịch vụ AI truy cập trực tiếp database nghiệp vụ của Java.

## Kiến trúc đích

```text
Browser
  -> Spring Boot (auth, quota, session, history, result)
      -> AI Generation Service (Python/FastAPI)
          -> LangGraph workflow
              -> LangChain model adapter
                  -> Ollama/Qwen3.5:9b local
                  -> provider cloud theo cấu hình

DSPy optimizer (offline) -> prompt/version artifacts -> AI Generation Service
PostgreSQL/Redis/RabbitMQ vẫn do Java sở hữu trong giai đoạn đầu
```

### Ranh giới trách nhiệm

| Thành phần | Trách nhiệm |
|---|---|
| Spring Boot | Xác thực người dùng, phân quyền, quota, tạo phiên, khóa lượt, lưu lịch sử và kết quả |
| AI Generation Service | Nhận context đã được phép gửi, tạo task, trả JSON theo hợp đồng, ghi trace và model/prompt version |
| LangChain | Chuẩn hóa model adapter, message, structured output và retry có giới hạn |
| LangGraph | Điều phối workflow có trạng thái: lập kế hoạch slot, sinh bài, tạo câu hỏi/fact, dịch, kiểm tra và sửa tối đa một vòng |
| DSPy | Tối ưu instruction và few-shot offline trên bộ dữ liệu cố định; xuất artifact được phiên bản hóa |
| Ollama/Qwen | Model local thực hiện inference; không chứa logic nghiệp vụ |

## Thư viện và công cụ đề xuất

| Nhóm | Chọn dùng | Cách dùng |
|---|---|---|
| HTTP service | `fastapi`, `uvicorn`, `pydantic`, `pydantic-settings`, `orjson` | API async, schema input/output, cấu hình qua environment |
| Model orchestration | `langchain`, `langchain-ollama` | Adapter cho Ollama/Qwen và structured output; giữ prompt/version rõ ràng |
| Stateful workflow | `langgraph` | Các node deterministic và LLM, timeout, nhánh sửa lỗi, trạng thái có thể kiểm tra |
| Prompt optimization | `dspy` | Chỉ chạy offline; dùng metric đã được hiệu chỉnh và tập kiểm tra tách biệt |
| HTTP nội bộ | `httpx` | Java gọi Python; timeout, connection pool và request id |
| Retry/resilience | `tenacity` hoặc cơ chế retry nhỏ trong service | Chỉ retry lỗi vận chuyển an toàn; không retry mù request đã có kết quả |
| Logging/metrics | `structlog`, `prometheus-client`, `opentelemetry-api`, `opentelemetry-sdk`, OTLP exporter | Trace id, latency, token, model, prompt version, lỗi theo node |
| Test | `pytest`, `pytest-asyncio`, `respx`, `hypothesis` | Contract test, workflow test, property test schema và lỗi mạng |
| Vận hành | Docker/Compose, health endpoints, pinned lock file | Chạy local và deploy độc lập; không phụ thuộc môi trường Python hệ thống |

LangSmith chỉ bật khi cần trace/evaluation ngoài hệ thống; không đưa API key hoặc dữ liệu người học vào đó nếu chưa có chính sách lưu trữ phù hợp.

## Action items

[ ] **1. Đóng băng baseline production.** Ghi lại request thực tế do `ConversationLocalClient.requestBody()` tạo ra, model `qwen3.5:9b`, schema, timeout, temperature, prompt version và response mẫu. Giữ bộ 15 input × 3 seed cùng các ca timeout, JSON lỗi và lượt 4 để so sánh sau mỗi thay đổi.

[ ] **2. Chốt hợp đồng API độc lập.** Tạo tài liệu OpenAPI cho `POST /v1/conversation/tasks`, `GET /health/live` và `GET /health/ready`. Input gồm `requestId`, `level`, `turnNumber`, scenario, roles, history, recent facts, practice focus và variation seed. Output gồm `slots`, `utteranceEn`, `facts`, `questionVi`, `translationVi`, `taskVi`, `skill`, `model`, `promptVersion`, `generationId`. Định nghĩa lỗi `400`, `409`, `429`, `504` và `503`.

[ ] **3. Tạo skeleton Python độc lập.** Thêm service riêng, ví dụ `services/ai-generation/`, với `pyproject.toml`, lock file, cấu hình Pydantic, FastAPI app, Dockerfile, `/live` và `/ready`. Chưa đổi logic prompt; trước tiên gọi Ollama bằng adapter tối thiểu để chứng minh output tương đương luồng Java.

[ ] **4. Chuyển model adapter sang LangChain.** Đóng gói Ollama/Qwen và provider cloud sau một interface chung. Đặt `temperature`, `num_ctx`, `num_predict`, timeout và model name trong cấu hình. Dùng structured output/Pydantic model nhưng vẫn giữ kiểm tra nghiệp vụ ở Java trong giai đoạn chuyển tiếp. Ghi `modelDigest` để biết chính xác model đã chạy.

[ ] **5. Chuyển workflow sang LangGraph theo từng node.** Tách các node: `build_context`, `select_slots`, `generate_english`, `extract_facts`, `write_question`, `translate`, `review`, `repair_once`. Trạng thái phải có `requestId`, `turnNumber`, `promptVersion`, `attempt`, deadline và lỗi node. Với lượt 4, đưa brief cuộc gặp mới vào state thay vì gửi nguyên câu chuyện cũ cho model.

[ ] **6. Giữ DSPy ở offline optimizer.** Viết module DSPy dùng cùng signature, input contract và adapter với service thật. Dùng judge đã đóng băng, tập train/dev/test tách biệt và metric theo nhóm: question–fact, translation, role, continuity, schema. Cho DSPy thử instruction/few-shot; xuất JSON artifact gồm prompt, demos, metric, dataset hash và model version. Không tự động đưa artifact vào production.

[ ] **7. Tạo lớp tương thích trong Java.** Thêm `ConversationAiClient` với hai implementation: local hiện tại và HTTP client tới Python. Feature flag chọn implementation theo user/session. Java gửi `Idempotency-Key`, `X-Request-Id` và deadline còn lại; không gửi credential người dùng hoặc dữ liệu thừa. Python không đọc trực tiếp bảng PostgreSQL của Java.

[ ] **8. Thiết kế idempotency và lỗi phân tán.** Một `requestId` chỉ được tạo một turn. Phân biệt timeout model, timeout HTTP, lỗi schema, rate limit và service unavailable. Không retry sau khi không biết provider đã sinh xong hay chưa nếu không có idempotency. Giữ circuit breaker/bulkhead để AI chậm không làm nghẽn request web.

[ ] **9. Bổ sung bảo mật nội bộ.** Chỉ cho phép Java gọi private network hoặc endpoint có service token/mTLS; xoay token qua secret store/environment. Giới hạn kích thước context, log redaction cho email/token và xác thực `requestId`. Không đưa API key cloud vào frontend hoặc Git.

[ ] **10. Kiểm thử theo tầng.** Chạy unit test Pydantic/schema, node test không gọi model, contract test Java–Python, Ollama integration test, timeout/retry/idempotency test và regression 15 × 3 seed. So sánh request canonical để bảo đảm LangChain không âm thầm đổi message hoặc thứ tự trường. Chấm output bằng judge cố định rồi kiểm tra thêm tập test chưa dùng.

[ ] **11. Chạy shadow mode.** Java vẫn trả kết quả từ client cũ nhưng gửi bản sao có kiểm soát sang Python. So sánh latency, lỗi, token và nội dung; không ghi đè dữ liệu học. Chỉ chuyển bước tiếp theo khi tỷ lệ lỗi vận chuyển và schema không cao hơn baseline, đồng thời nội dung không giảm trên tập regression.

[ ] **12. Canary và rollback.** Bật Python cho một tỷ lệ nhỏ phiên hoặc tài khoản thử nghiệm. Theo dõi p50/p95/p99 latency, timeout, schema error, model error, queue depth, CPU/RAM và chi phí. Giữ cờ quay về client cũ trong một lần triển khai; rollback JAR hoặc container không làm mất lịch sử phiên.

[ ] **13. Chuyển quyền vận hành.** Khi canary ổn định, chuyển dần toàn bộ conversation generation sang Python. Giữ client cũ trong một release dự phòng, sau đó mới xóa code trùng. Cập nhật runbook, dashboard, alert, backup và quy trình đổi model/prompt có approval.

[ ] **14. Đánh giá các service tiếp theo.** Chỉ tách TTS, grading hoặc coach khi có nhu cầu độc lập về tải, triển khai hoặc dữ liệu. Mỗi service mới phải sở hữu domain data của mình; không tạo các service chỉ chia theo tầng controller/service/repository.

## Hợp đồng workflow dự kiến

```text
Java -> POST /v1/conversation/tasks
      -> validate input + create request id
      -> build_context
      -> select_slots
      -> generate_english
      -> extract_facts
      -> write_question
      -> translate
      -> review
          ├─ pass -> response
          └─ fail -> repair_once -> review -> response/error
```

`review` trong service chỉ kiểm tra các điều kiện có thể xác định và trả lỗi có cấu trúc. Judge Claude dùng cho đánh giá offline, không được coi là bằng chứng tuyệt đối để tự động sửa nội dung production.

## Tiêu chí hoàn thành giai đoạn đầu

- Java gọi được Python bằng hợp đồng versioned và có idempotency.
- Python chạy được với Ollama/Qwen3.5:9b, trả đúng schema và health check.
- Request canonical của Python tương đương request production đã đóng băng.
- Regression 15 × 3 seed có báo cáo tách riêng lỗi transport, schema và nội dung.
- Shadow mode không làm thay đổi dữ liệu phiên học.
- Có feature flag và rollback về client cũ.
- DSPy xuất được artifact prompt có hash; chưa tự động phát hành artifact.

## Rủi ro cần kiểm soát

- Tách service nhưng dùng chung database sẽ tạo distributed monolith; giai đoạn đầu phải coi Java là data owner và giao tiếp qua API.
- LangChain có thể thay đổi cách đóng gói message/structured output; cần canonical request test.
- LangGraph làm workflow rõ hơn nhưng tăng độ phức tạp; chỉ dùng cho các nhánh có trạng thái thật sự.
- DSPy tối ưu theo judge; nếu judge sai, prompt được chọn cũng sai. Tập test độc lập và nhãn người duyệt vẫn cần thiết.
- Qwen local phụ thuộc CPU/RAM; timeout, concurrency và `num_ctx` phải đo trên máy triển khai thật.
- Tách dịch vụ làm tăng latency mạng và điểm lỗi; shadow/canary phải đo p95 và lỗi timeout trước khi chuyển hẳn.

## Open questions

- Dịch vụ Python sẽ chạy cùng máy với Ollama hay trên host riêng trong production?
- Giai đoạn đầu có cần chạy workflow bất đồng bộ qua RabbitMQ, hay giữ HTTP đồng bộ cho từng lượt?
- Có yêu cầu lưu prompt/output đầy đủ để audit hay chỉ lưu hash, metadata và lỗi đã được redaction?
