CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    role ENUM('manager', 'kitchen_staff') NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username)
);

CREATE TABLE suppliers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    contact_email VARCHAR(254) NULL,
    phone VARCHAR(32) NULL,
    PRIMARY KEY (id),
    KEY idx_suppliers_name (name)
);

CREATE TABLE items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    amount DECIMAL(12, 3) NOT NULL DEFAULT 0,
    min_stock DECIMAL(12, 3) NOT NULL DEFAULT 0,
    avg_cost DECIMAL(12, 4) NOT NULL DEFAULT 0,
    last_alert_date DATE NULL,
    PRIMARY KEY (id),
    KEY idx_items_name (name),
    CONSTRAINT chk_items_amount_nonnegative CHECK (amount >= 0),
    CONSTRAINT chk_items_min_stock_nonnegative CHECK (min_stock >= 0),
    CONSTRAINT chk_items_avg_cost_nonnegative CHECK (avg_cost >= 0)
);

CREATE TABLE item_suppliers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    item_id BIGINT UNSIGNED NOT NULL,
    supplier_id BIGINT UNSIGNED NOT NULL,
    price DECIMAL(12, 4) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_item_suppliers_item_supplier (item_id, supplier_id),
    KEY idx_item_suppliers_supplier_id (supplier_id),
    CONSTRAINT chk_item_suppliers_price_positive CHECK (price > 0),
    CONSTRAINT fk_item_suppliers_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_suppliers_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id) ON DELETE CASCADE
);

CREATE TABLE price_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    item_supplier_id BIGINT UNSIGNED NOT NULL,
    old_price DECIMAL(12, 4) NULL,
    new_price DECIMAL(12, 4) NOT NULL,
    changed_by BIGINT UNSIGNED NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_price_history_item_supplier_changed_at (item_supplier_id, changed_at),
    KEY idx_price_history_changed_by (changed_by),
    CONSTRAINT chk_price_history_new_price_positive CHECK (new_price > 0),
    CONSTRAINT chk_price_history_old_price_positive CHECK (old_price IS NULL OR old_price > 0),
    CONSTRAINT fk_price_history_item_supplier
        FOREIGN KEY (item_supplier_id) REFERENCES item_suppliers (id) ON DELETE CASCADE,
    CONSTRAINT fk_price_history_user
        FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE storage_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    item_id BIGINT UNSIGNED NULL,
    action ENUM('restock', 'usage', 'waste') NOT NULL,
    amount_changed DECIMAL(12, 3) NOT NULL,
    `date` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500) NULL,
    staff_name VARCHAR(150) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    unit_cost DECIMAL(12, 4) NOT NULL DEFAULT 0,
    supplier_id BIGINT UNSIGNED NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    PRIMARY KEY (id),
    KEY idx_storage_logs_item_date (item_id, `date`),
    KEY idx_storage_logs_status_date (status, `date`),
    KEY idx_storage_logs_user_id (user_id),
    KEY idx_storage_logs_supplier_id (supplier_id),
    CONSTRAINT chk_storage_logs_amount_positive CHECK (amount_changed > 0),
    CONSTRAINT chk_storage_logs_unit_cost_nonnegative CHECK (unit_cost >= 0),
    CONSTRAINT fk_storage_logs_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE SET NULL,
    CONSTRAINT fk_storage_logs_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_storage_logs_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id) ON DELETE SET NULL
);

CREATE TABLE email_settings (
    id TINYINT UNSIGNED NOT NULL,
    recipient_email VARCHAR(254) NOT NULL,
    frequency ENUM('daily', 'weekly') NOT NULL DEFAULT 'daily',
    send_time TIME NOT NULL DEFAULT '18:00:00',
    day_of_week TINYINT UNSIGNED NULL,
    updated_by BIGINT UNSIGNED NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_email_settings_singleton CHECK (id = 1),
    CONSTRAINT chk_email_settings_day_of_week
        CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT fk_email_settings_user
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE email_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    item_name VARCHAR(150) NULL,
    message TEXT NOT NULL,
    status ENUM('sent', 'failed') NOT NULL,
    error_message VARCHAR(250) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_email_notifications_subject_created_at (subject, created_at)
);
