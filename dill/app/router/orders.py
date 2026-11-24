"""Customer order submission and authenticated manager order operations."""

import logging
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request, Response

from ..core.security import require_app_key, require_manager
from ..model.order_repository import OrderRepository
from ..schema.order import OrderCreate, OrderRecord, OrderStatus, StatusChange


router = APIRouter(prefix="/v1", tags=["orders"])
logger = logging.getLogger("dill.orders")


def get_repository(request: Request) -> OrderRepository:
    return request.app.state.order_repository


Repository = Annotated[OrderRepository, Depends(get_repository)]


@router.post("/orders", response_model=OrderRecord, status_code=201, dependencies=[Depends(require_app_key)])
def create_order(order: OrderCreate, response: Response, request: Request, repository: Repository):
    record, created = repository.create(order)
    response.status_code = 201 if created else 200
    if created:
        request.app.state.order_events.notify(record["orderId"], record["storeId"])
    logger.info("주문을 접수했습니다." if created else "기존 주문 접수를 확인했습니다.", extra={
        "event": "order_created" if created else "order_replayed",
        "order_id": record["orderId"], "store_id": record["storeId"],
    })
    return record


@router.get("/orders", dependencies=[Depends(require_manager)])
def list_orders(
    repository: Repository,
    storeId: Annotated[str | None, Query(max_length=160)] = None,
    status: OrderStatus | None = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 100,
    offset: Annotated[int, Query(ge=0, le=1_000_000)] = 0,
):
    return repository.list_orders(storeId.strip() or None if storeId else None, status, limit, offset)


@router.get("/orders/{order_id}/status", dependencies=[Depends(require_app_key)])
def get_order_status(order_id: UUID, repository: Repository, response: Response):
    record = repository.get(str(order_id))
    response.headers["Cache-Control"] = "no-store"
    return {
        "orderId": record["orderId"],
        "status": record["status"],
        "pickupCode": record["pickupCode"],
    }


@router.get("/orders/{order_id}", response_model=OrderRecord, dependencies=[Depends(require_manager)])
def get_order(order_id: UUID, repository: Repository):
    return repository.get(str(order_id))


@router.patch("/orders/{order_id}/status", response_model=OrderRecord, dependencies=[Depends(require_manager)])
def update_order_status(order_id: UUID, change: StatusChange, request: Request, repository: Repository):
    record = repository.change_status(str(order_id), change.status)
    request.app.state.order_events.notify(record["orderId"], record["storeId"])
    logger.info("주문 처리 상태를 반영했습니다.", extra={
        "event": "order_status_updated", "order_id": record["orderId"], "store_id": record["storeId"],
    })
    return record


@router.get("/stores", dependencies=[Depends(require_manager)])
def list_stores(repository: Repository):
    return {"stores": repository.list_stores()}
