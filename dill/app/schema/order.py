"""Pydantic request/response schemas for the camelCase order HTTP contract."""

from datetime import datetime, timezone
from enum import Enum
from typing import Annotated
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, StringConstraints, field_serializer


StoreId = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
ShortName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
MenuId = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=240)]
Options = Annotated[str, StringConstraints(strip_whitespace=True, max_length=1000)]
Note = Annotated[str, StringConstraints(strip_whitespace=True, max_length=2000)]
Quantity = Annotated[int, Field(strict=True, ge=1, le=20)]
Price = Annotated[int, Field(strict=True, ge=0, le=10_000_000)]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class OrderItem(ContractModel):
    menuItemId: MenuId
    name: ShortName
    quantity: Quantity
    unitPriceKrw: Price | None = None
    options: Options = ""


class OrderCreate(ContractModel):
    clientRequestId: UUID
    storeId: StoreId
    storeName: ShortName
    items: Annotated[list[OrderItem], Field(min_length=1, max_length=60)]
    customerNote: Note = ""


class OrderStatus(str, Enum):
    RECEIVED = "RECEIVED"
    PREPARING = "PREPARING"
    READY = "READY"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"


class StatusChange(ContractModel):
    status: OrderStatus


class OrderRecord(ContractModel):
    model_config = ConfigDict(extra="forbid", from_attributes=True)

    orderId: UUID = Field(validation_alias=AliasChoices("orderId", "order_id"))
    clientRequestId: UUID = Field(validation_alias=AliasChoices("clientRequestId", "client_request_id"))
    storeId: str = Field(validation_alias=AliasChoices("storeId", "store_id"))
    storeName: str = Field(validation_alias=AliasChoices("storeName", "store_name"))
    pickupCode: str = Field(validation_alias=AliasChoices("pickupCode", "pickup_code"))
    status: OrderStatus
    createdAt: datetime = Field(validation_alias=AliasChoices("createdAt", "created_at"))
    updatedAt: datetime = Field(validation_alias=AliasChoices("updatedAt", "updated_at"))
    items: list[OrderItem]
    customerNote: str = Field(validation_alias=AliasChoices("customerNote", "customer_note"))
    totalPriceKrw: int | None = Field(validation_alias=AliasChoices("totalPriceKrw", "total_price_krw"))

    @field_serializer("createdAt", "updatedAt", when_used="json")
    def serialize_timestamp(self, value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
