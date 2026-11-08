-- Decision 130 (D-046): a seller's closed month hands its carriedOut to exactly one later closing.
-- NULL source months (first closings) stay unrestricted. Fails without changing the table if duplicates already exist.
ALTER TABLE settlement_seller_carryover ADD UNIQUE KEY uk_seller_carryover_source (seller_subject,source_month);
