"""Sanitized ASGI request logs with a request ID shared by route and worker logs."""

import logging
import re
from time import perf_counter
from uuid import uuid4

from .logger import reset_request_id, set_request_id


logger = logging.getLogger("dill.http")
REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{1,128}")


class RequestLoggingMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = ""
        for name, value in scope.get("headers", []):
            if name.lower() == b"x-request-id":
                candidate = value.decode("ascii", errors="replace")
                if REQUEST_ID.fullmatch(candidate):
                    request_id = candidate
                break
        request_id = request_id or uuid4().hex
        token = set_request_id(request_id)
        started = perf_counter()
        finished = None
        status = 500
        response_started = False
        disconnected = False
        error_type = None

        async def receive_request():
            nonlocal disconnected
            message = await receive()
            if message["type"] == "http.disconnect":
                disconnected = True
            return message

        async def send_response(message):
            nonlocal status, response_started, finished
            if message["type"] == "http.response.start":
                response_started = True
                status = message["status"]
                headers = [(name, value) for name, value in message.get("headers", [])
                           if name.lower() != b"x-request-id"]
                headers.append((b"x-request-id", request_id.encode("ascii")))
                message = {**message, "headers": headers}
            elif message["type"] == "http.response.body" and not message.get("more_body", False):
                finished = perf_counter()
            await send(message)

        try:
            await self.app(scope, receive_request, send_response)
        except Exception as error:
            error_type = type(error).__name__
            if isinstance(error, OSError):
                disconnected = True
            logger.error("요청 처리 중 오류가 발생했습니다.", exc_info=True,
                         extra={"event": "request_error", "error_type": error_type})
            raise
        finally:
            try:
                if disconnected and not response_started and error_type is None:
                    status = 499
                # Route templates contain no query values, credentials, or submitted data.
                route = getattr(scope.get("route"), "path", None) or "<unmatched>"
                level = logging.ERROR if status >= 500 else logging.WARNING if status >= 400 else logging.INFO
                logger.log(level, "HTTP 요청을 처리했습니다.", extra={
                    "event": "http_request",
                    "method": scope.get("method", ""),
                    "path": route,
                    "status_code": status,
                    "duration_ms": round(((finished or perf_counter()) - started) * 1000, 2),
                    "client_disconnected": disconnected,
                })
            finally:
                reset_request_id(token)
