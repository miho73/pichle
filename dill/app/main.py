"""FastAPI assembly; routes, schemas, persistence, and infrastructure live separately."""

from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy.exc import SQLAlchemyError

from .core.config import BASE_DIR, ConfigError, LogConfig, load_config
from .core.logger import setup_logging
from .core.brand_menus import BrandMenuService
from .core.middleware import RequestSizeLimit
from .core.order_events import OrderEvents
from .core.request_logging import RequestLoggingMiddleware
from .core.security import install_manager_auth
from .model.order_repository import OrderConflict, OrderNotFound, OrderRepository
from .router import manager, menus, orders, pages, realtime


setup_logging(LogConfig())
logger = logging.getLogger("dill")
try:
    config = load_config()
except ConfigError as error:
    logger.error("%s", error, extra={"event": "config_error", "error_type": type(error).__name__})
    raise
setup_logging(config.log)
repository = OrderRepository(config.database)
order_events = OrderEvents()
brand_menus = BrandMenuService()


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        repository.check_ready()
    except SQLAlchemyError as error:
        logger.error("PostgreSQL 연결 또는 주문 테이블을 확인하지 못했습니다. DB 설정과 init.sql 실행 여부를 확인해주세요.",
                     extra={"event": "database_startup_failed", "error_type": type(error).__name__})
        repository.dispose()
        raise RuntimeError("PostgreSQL 연결 또는 주문 테이블 준비가 필요합니다. DB 설정과 init.sql 실행 여부를 확인해주세요.") from None
    await order_events.start()
    logger.info("주문 서버를 시작했습니다.", extra={"event": "server_started"})
    try:
        yield
    finally:
        try:
            await order_events.stop()
        finally:
            try:
                await brand_menus.close()
            finally:
                repository.dispose()
                logger.info("주문 서버를 종료했습니다.", extra={"event": "server_stopped"})


app = FastAPI(title="Dill · Pichle Orders", version="0.1.0", lifespan=lifespan)
app.state.config = config
app.state.order_repository = repository
app.state.order_events = order_events
app.state.brand_menus = brand_menus
app.add_middleware(RequestSizeLimit)
install_manager_auth(app, config)
app.add_middleware(RequestLoggingMiddleware)
app.mount("/static", StaticFiles(directory=BASE_DIR / "static", check_dir=False), name="static")
app.include_router(pages.router)
app.include_router(orders.router)
app.include_router(manager.router)
app.include_router(menus.router)
app.include_router(realtime.router)


@app.exception_handler(OrderNotFound)
async def order_not_found(request, error):
    return JSONResponse(status_code=404, content={"detail": "주문을 찾지 못했습니다."})


@app.exception_handler(OrderConflict)
async def order_conflict(request, error):
    return JSONResponse(status_code=409, content={"detail": str(error)})


@app.exception_handler(SQLAlchemyError)
async def database_error(request, error):
    logger.error("주문 저장소 처리에 실패했습니다.",
                 extra={"event": "database_error", "error_type": type(error).__name__})
    return JSONResponse(status_code=503, content={"detail": "주문을 처리하지 못했습니다. 잠시 후 다시 시도해주세요."})
