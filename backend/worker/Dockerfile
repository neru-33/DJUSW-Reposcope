FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY backend/worker/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY backend/worker/ /app/backend/worker/
COPY backend/migrations/ /app/backend/migrations/
USER 65532:65532
CMD ["python", "-m", "backend.worker"]
