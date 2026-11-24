"""Transactional order repository using SQLAlchemy ORM and Pydantic schemas."""

from datetime import datetime, timezone
import hashlib
import json
from uuid import UUID, uuid4

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert

from ..core.config import DatabaseConfig
from ..core.database import Database
from .order import Order
from ..schema.order import OrderCreate, OrderItem, OrderRecord, OrderStatus, StatusChange


class OrderConflict(Exception):
    pass


class OrderNotFound(Exception):
    pass


TRANSITIONS = {
    OrderStatus.RECEIVED: {OrderStatus.PREPARING, OrderStatus.CANCELLED},
    OrderStatus.PREPARING: {OrderStatus.READY, OrderStatus.CANCELLED},
    OrderStatus.READY: {OrderStatus.COMPLETED, OrderStatus.CANCELLED},
    OrderStatus.COMPLETED: set(),
    OrderStatus.CANCELLED: set(),
}


class OrderRepository:
    def __init__(self, database: DatabaseConfig):
        self.database = Database(database)

    def check_ready(self):
        # Schema provisioning is explicit: operators run init.sql before starting the service.
        with self.database.sessions.begin() as session:
            session.execute(select(Order).limit(0))

    def dispose(self):
        self.database.dispose()

    @staticmethod
    def _record(order: Order) -> dict:
        return OrderRecord.model_validate(order).model_dump(mode="json")

    def create(self, order: OrderCreate) -> tuple[dict, bool]:
        payload = order.model_dump(mode="json")
        canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        request_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        order_id = uuid4()
        timestamp = datetime.now(timezone.utc)
        statement = (
            insert(Order)
            .values(
                order_id=order_id,
                client_request_id=order.clientRequestId,
                store_id=order.storeId,
                store_name=order.storeName,
                pickup_code=f"pending-{order_id}",
                status=OrderStatus.RECEIVED.value,
                created_at=timestamp,
                updated_at=timestamp,
                request_hash=request_hash,
                payload=payload,
            )
            .on_conflict_do_nothing(index_elements=[Order.client_request_id])
            .returning(Order)
        )
        with self.database.sessions.begin() as session:
            inserted = session.scalars(statement).first()
            if inserted is None:
                # READ COMMITTED gets a new snapshot after the conflicting insertion commits.
                existing = session.scalar(select(Order).where(Order.client_request_id == order.clientRequestId))
                if existing is None or existing.request_hash != request_hash:
                    raise OrderConflict("같은 전송 번호로 다른 주문이 전달되었습니다. 주문 내용을 다시 확인해주세요.")
                return self._record(existing), False
            inserted.pickup_code = f"P-{inserted.seq:06d}"
            session.flush()
            return self._record(inserted), True

    def get(self, order_id: str) -> dict:
        with self.database.sessions.begin() as session:
            order = session.scalar(select(Order).where(Order.order_id == UUID(order_id)))
            if order is None:
                raise OrderNotFound()
            return self._record(order)

    def list_orders(self, store_id: str | None, status: OrderStatus | None, limit: int, offset: int) -> dict:
        filters = []
        if store_id:
            filters.append(Order.store_id == store_id)
        if status is not None:
            filters.append(Order.status == status.value)
        # Both reads share one REPEATABLE READ transaction and therefore one page/count snapshot.
        with self.database.snapshot_sessions.begin() as session:
            total = session.scalar(select(func.count()).select_from(Order).where(*filters))
            orders = session.scalars(
                select(Order).where(*filters).order_by(Order.seq.desc()).limit(limit).offset(offset)
            ).all()
            return {"orders": [self._record(order) for order in orders], "total": total}

    def list_stores(self) -> list[dict]:
        latest = select(Order.store_id, func.max(Order.seq).label("seq")).group_by(Order.store_id).subquery()
        statement = (
            select(Order.store_id.label("storeId"), Order.store_name.label("storeName"))
            .join(latest, Order.seq == latest.c.seq)
            .order_by(func.lower(Order.store_name), Order.store_id)
        )
        with self.database.sessions.begin() as session:
            return [dict(row) for row in session.execute(statement).mappings()]

    def change_status(self, order_id: str, target: OrderStatus) -> dict:
        with self.database.sessions.begin() as session:
            order = session.scalar(select(Order).where(Order.order_id == UUID(order_id)).with_for_update())
            if order is None:
                raise OrderNotFound()
            current = OrderStatus(order.status)
            if target == current:
                return self._record(order)
            if target not in TRANSITIONS[current]:
                raise OrderConflict("변경할 수 없는 주문 상태입니다. 최신 주문을 확인해주세요.")
            order.status = target.value
            order.updated_at = datetime.now(timezone.utc)
            session.flush()
            return self._record(order)
