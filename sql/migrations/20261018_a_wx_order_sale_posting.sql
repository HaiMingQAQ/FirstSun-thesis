-- 44: Online fulfillment posting needs freight separated from goods and the payment-time cost precision.
-- Existing 01-43 files remain immutable. No seed, stock adjustment, or historical order backfill.
SET @posting_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ph_sale_order' AND column_name='freight_amount')=0,
    'ALTER TABLE ph_sale_order ADD COLUMN freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''线上配送费，柜台为0'' AFTER subtotal',
    'SELECT 1');
PREPARE posting_stmt FROM @posting_ddl;
EXECUTE posting_stmt;
DEALLOCATE PREPARE posting_stmt;

SET @posting_ddl = IF((SELECT numeric_scale FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='ph_sale_order_line' AND column_name='cost_price')=2,
    'ALTER TABLE ph_sale_order_line MODIFY COLUMN cost_price DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT ''支付出库成本快照''',
    'SELECT 1');
PREPARE posting_stmt FROM @posting_ddl;
EXECUTE posting_stmt;
DEALLOCATE PREPARE posting_stmt;
