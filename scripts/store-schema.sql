-- Existing development MySQL: create Store's schema without changing other services' data.
CREATE DATABASE IF NOT EXISTS stove_store DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON stove_store.* TO 'stove'@'%';
FLUSH PRIVILEGES;
