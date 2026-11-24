"""JSON logging and daily rotation for the single-process Dill service."""

from contextvars import ContextVar, Token
from datetime import datetime, time as day_time, timedelta, timezone
import json
import logging
from logging.handlers import TimedRotatingFileHandler
import math
import os
from pathlib import Path
import re
import sys
from threading import RLock
import time
import traceback


request_id_context: ContextVar[str] = ContextVar("dill_request_id", default="")
_setup_lock = RLock()
_setup_signature = None
_EXTRA_FIELDS = {
    "event", "method", "path", "route", "status_code", "duration_ms",
    "error_type", "order_id", "store_id", "client_disconnected",
}
_SECRET = re.compile(
    r"(?i)([\"']?(?:password|passwd|pwd|secret|session_secret|token|api[_-]?key|"
    r"authorization|cookie|set-cookie)[\"']?\s*[:=]\s*)"
    r"(?:\"[^\"]*\"|'[^']*'|[^\s,;]+)"
)
_AUTHORIZATION = re.compile(r"(?i)\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=\-]+")
_URL_CREDENTIALS = re.compile(r"(?i)\b((?:postgres(?:ql)?|https?)://)[^\s/@]+:[^\s/@]*@")


def get_request_id() -> str:
    return request_id_context.get()


def set_request_id(value: str) -> Token:
    return request_id_context.set(value[:128] if isinstance(value, str) else "")


def reset_request_id(token: Token) -> None:
    request_id_context.reset(token)


def _timezone(name: str):
    if name == "Asia/Seoul":
        return timezone(timedelta(hours=9), "KST")
    if name == "UTC":
        return timezone.utc
    raise ValueError("log.timezone은 Asia/Seoul 또는 UTC로 지정해주세요.")


def _redact(value: str) -> str:
    value = _AUTHORIZATION.sub("[REDACTED]", value)
    value = _URL_CREDENTIALS.sub(r"\1[REDACTED]@", value)
    return _SECRET.sub(r"\1[REDACTED]", value)[:8192]


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = get_request_id()
        return True


class JsonFormatter(logging.Formatter):
    def __init__(self, timezone_name: str):
        super().__init__()
        self.timezone = _timezone(timezone_name)

    def format(self, record: logging.LogRecord) -> str:
        try:
            message = record.getMessage()
        except (TypeError, ValueError):
            message = "로그 메시지 형식을 확인해주세요."
        data = {
            "timestamp": datetime.fromtimestamp(record.created, self.timezone).isoformat(timespec="milliseconds"),
            "level": record.levelname.upper(),
            "name": record.name,
            "message": _redact(message),
            "request_id": getattr(record, "request_id", get_request_id()),
            "filename": record.filename,
            "lineno": record.lineno,
        }
        # Request bodies, cookies, credentials, and arbitrary extra objects are never serialized.
        for key in _EXTRA_FIELDS:
            if key not in record.__dict__:
                continue
            value = record.__dict__[key]
            if isinstance(value, str):
                if key in {"path", "route"}:
                    value = value.split("?", 1)[0].split("#", 1)[0]
                data[key] = _redact(value)
            elif isinstance(value, float):
                data[key] = value if math.isfinite(value) else None
            elif value is None or isinstance(value, (bool, int)):
                data[key] = value
        if record.exc_info and record.exc_info[0] is not None:
            # Exception strings and source lines can contain DSNs or submitted values.
            data["exception_type"] = record.exc_info[0].__name__
            data["stack"] = [
                {"filename": Path(frame.filename).name, "lineno": frame.lineno, "function": frame.name}
                for frame in traceback.extract_tb(record.exc_info[2])[-24:]
            ]
        return json.dumps(data, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


class DailyRotatingFileHandler(TimedRotatingFileHandler):
    """Rotate on the first write after local midnight, independent of the host timezone.

    The deployment runs one Uvicorn process per container. Multiple worker processes
    must not share this file handler's target.
    """

    def __init__(self, filename: Path, backup_count: int, timezone_name: str):
        self.timezone = _timezone(timezone_name)
        super().__init__(filename, when="midnight", interval=1, backupCount=backup_count,
                         encoding="utf-8", delay=True, utc=True, errors="backslashreplace")

    def computeRollover(self, current_time):
        date = datetime.fromtimestamp(current_time, self.timezone).date() + timedelta(days=1)
        return int(datetime.combine(date, day_time.min, tzinfo=self.timezone).timestamp())

    def doRollover(self):
        if self.stream is not None:
            self.stream.close()
            self.stream = None
        date = datetime.fromtimestamp(self.rolloverAt - 1, self.timezone).strftime("%Y-%m-%d")
        destination = self.rotation_filename(f"{self.baseFilename}.{date}")
        # Never overwrite a previous archive if an operator restored or replaced app.log.
        suffix = 1
        original = destination
        while os.path.exists(destination):
            destination = f"{original}.{suffix}"
            suffix += 1
        self.rotate(self.baseFilename, destination)
        if self.backupCount > 0:
            for filename in self.getFilesToDelete():
                os.remove(filename)
        if not self.delay:
            self.stream = self._open()
        self.rolloverAt = self.computeRollover(time.time())

    def getFilesToDelete(self):
        base = Path(self.baseFilename)
        pattern = re.compile(re.escape(base.name) + r"\.(\d{4}-\d{2}-\d{2})(?:\.(\d+))?$")
        archives = []
        for entry in base.parent.iterdir():
            match = pattern.fullmatch(entry.name)
            if match and entry.is_file():
                archives.append((match.group(1), int(match.group(2) or 0), str(entry)))
        archives.sort()
        return [entry[2] for entry in archives[:-self.backupCount]] if self.backupCount > 0 else []


def setup_logging(config) -> None:
    """Install one JSON handler set using the application's validated LogConfig."""
    global _setup_signature
    signature = (config.level, str(config.path), config.backup_count, config.timezone, tuple(config.handlers))
    with _setup_lock:
        root = logging.getLogger()
        if _setup_signature == signature and any(getattr(handler, "_dill_managed", False) for handler in root.handlers):
            return
        level = getattr(logging, config.level.upper(), logging.INFO)
        formatter = JsonFormatter(config.timezone)
        handlers = []
        if "terminal" in config.handlers:
            handlers.append(logging.StreamHandler(sys.stdout))
        if "file" in config.handlers:
            path = Path(config.path)
            path.parent.mkdir(parents=True, exist_ok=True)
            handlers.append(DailyRotatingFileHandler(path, config.backup_count, config.timezone))
        for handler in handlers:
            handler.setLevel(level)
            handler.setFormatter(formatter)
            handler.addFilter(RequestIdFilter())
            handler._dill_managed = True
        previous = list(root.handlers)
        root.handlers.clear()
        root.setLevel(level)
        for handler in handlers:
            root.addHandler(handler)
        for handler in previous:
            handler.close()
        for name in ("uvicorn", "uvicorn.error", "dill", "fastapi", "starlette"):
            logger = logging.getLogger(name)
            for handler in logger.handlers[:]:
                logger.removeHandler(handler)
                if handler not in previous:
                    handler.close()
            logger.disabled = False
            logger.setLevel(level)
            logger.propagate = True
        # The ASGI middleware writes sanitized access records with request IDs instead.
        access = logging.getLogger("uvicorn.access")
        access.handlers.clear()
        access.propagate = False
        access.disabled = True
        logging.getLogger("psycopg").setLevel(max(level, logging.WARNING))
        logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
        logging.getLogger("sqlalchemy.pool").setLevel(logging.WARNING)
        logging.getLogger("websockets").setLevel(logging.WARNING)
        logging.getLogger("uvicorn.error").setLevel(max(level, logging.INFO))
        _setup_signature = signature
        logging.getLogger("dill").info("JSON 로깅을 시작했습니다.", extra={"event": "logging_initialized"})
