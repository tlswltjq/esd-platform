CREATE INDEX ix_purchase_order_expiry ON purchase_order(status, created_at, id);
