-- 서비스별 독립 스키마 (Database per Service)
CREATE DATABASE IF NOT EXISTS stove_studio DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_review DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_catalog DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_payment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_license DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS stove_settlement DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- store: Elasticsearch, download: MongoDB → RDB 스키마 없음

GRANT ALL PRIVILEGES ON stove_studio.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_review.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_catalog.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_order.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_payment.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_license.* TO 'stove'@'%';
GRANT ALL PRIVILEGES ON stove_settlement.* TO 'stove'@'%';
FLUSH PRIVILEGES;
