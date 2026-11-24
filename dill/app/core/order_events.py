"""Coalesced in-process order notifications for the single-worker service."""

import asyncio


class OrderEvents:
    def __init__(self):
        self._loop = None
        self._running = False
        self._subscribers: set[asyncio.Queue] = set()

    async def start(self):
        loop = asyncio.get_running_loop()
        if self._running and self._loop is not loop:
            raise RuntimeError("주문 알림은 서비스 이벤트 루프에서 시작해야 합니다.")
        self._loop = loop
        self._running = True

    async def stop(self):
        self._running = False
        for queue in tuple(self._subscribers):
            if queue.full():
                queue.get_nowait()
            queue.put_nowait({"type": "SHUTDOWN"})
        self._subscribers.clear()
        self._loop = None

    def subscribe(self) -> asyncio.Queue:
        if not self._running or asyncio.get_running_loop() is not self._loop:
            raise RuntimeError("주문 알림 서비스가 준비되지 않았습니다.")
        queue = asyncio.Queue(maxsize=1)
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue):
        self._subscribers.discard(queue)

    def notify(self, order_id: str | None = None, store_id: str | None = None):
        """Call after committing an order, including from a synchronous route thread."""
        loop = self._loop
        if not self._running or loop is None:
            return
        try:
            loop.call_soon_threadsafe(self._publish, order_id, store_id)
        except RuntimeError:
            # Shutdown may finish between observing the loop and scheduling a callback.
            return

    def _publish(self, order_id: str | None, store_id: str | None):
        if not self._running:
            return
        event = {"type": "ORDERS_CHANGED", "orderId": order_id, "storeId": store_id}
        for queue in tuple(self._subscribers):
            # One invalidation is sufficient: the consumer reads a fresh DB snapshot.
            if not queue.full():
                queue.put_nowait(event)
