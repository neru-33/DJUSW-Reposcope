# 로컬 수집 구성의 ingest 서비스. 빌드 컨텍스트는 webhook/ (infra/local/compose.yml)
FROM python:3.13-slim

# Lambda 런타임에는 boto3가 들어 있으므로 로컬 이미지에만 설치한다. 버전은 worker와 같다.
RUN pip install --no-cache-dir boto3==1.43.123

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY src/ /app/src/
COPY local/server.py /app/server.py

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER app
EXPOSE 8080
CMD ["python", "server.py"]
