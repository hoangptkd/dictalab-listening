FROM python:3.12-slim
WORKDIR /app
ENV DICTALAB_AI_REGISTRY_DIR=/app/artifacts/prompts
COPY services/ai-generation/requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
COPY services/ai-generation/app ./app
COPY artifacts/prompts ./artifacts/prompts
EXPOSE 8091
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
