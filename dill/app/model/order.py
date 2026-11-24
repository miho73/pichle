"""Persistent order entities. Wire validation and serialization live in app.schema.order."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, CheckConstraint, DateTime, Identity, Index, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID as PostgreSQLUUID
from sqlalchemy.orm import Mapped, mapped_column

from ..core.database import Base


class Order(Base):
    __tablename__ = "pichle_orders"
    __table_args__ = (
        CheckConstraint(
            "status IN ('RECEIVED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED')",
            name="pichle_orders_status_check",
        ),
        {"schema": "public"},
    )

    seq: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    order_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), unique=True, nullable=False)
    client_request_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), unique=True, nullable=False)
    store_id: Mapped[str] = mapped_column(Text, nullable=False)
    store_name: Mapped[str] = mapped_column(Text, nullable=False)
    pickup_code: Mapped[str] = mapped_column(Text, unique=True, nullable=False)
    status: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    request_hash: Mapped[str] = mapped_column(Text, nullable=False)
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False)

    @property
    def items(self) -> list[dict]:
        return self.payload["items"]

    @property
    def customer_note(self) -> str:
        return self.payload["customerNote"]

    @property
    def total_price_krw(self) -> int | None:
        if any(item["unitPriceKrw"] is None for item in self.items):
            return None
        return sum(item["unitPriceKrw"] * item["quantity"] for item in self.items)


Index("pichle_orders_store_seq", Order.store_id, Order.seq.desc())
Index("pichle_orders_status_seq", Order.status, Order.seq.desc())
