-- Run as the database user configured in config.yml, after connecting to that database.
-- Creates missing objects only. Existing orders are preserved.
BEGIN;

CREATE TABLE IF NOT EXISTS public.pichle_orders (
    seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    client_request_id UUID NOT NULL UNIQUE,
    store_id TEXT NOT NULL,
    store_name TEXT NOT NULL,
    pickup_code TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    request_hash TEXT NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT pichle_orders_status_check CHECK (
        status IN ('RECEIVED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED')
    )
);

CREATE INDEX IF NOT EXISTS pichle_orders_store_seq
    ON public.pichle_orders (store_id, seq DESC);

CREATE INDEX IF NOT EXISTS pichle_orders_status_seq
    ON public.pichle_orders (status, seq DESC);

COMMIT;
