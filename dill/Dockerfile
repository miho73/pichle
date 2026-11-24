FROM python:3.12-slim

ARG APP_COMMIT=unknown
LABEL org.opencontainers.image.revision=$APP_COMMIT

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DILL_CONFIG_PATH=/app/config.yml

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir -r requirements.txt

COPY *.py ./
COPY app ./app
COPY static ./static
RUN mkdir -p /app/logs

EXPOSE 8000

CMD ["python", "run.py"]
