-- Users table
CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    token VARCHAR(128) UNIQUE NOT NULL,
    default_provider VARCHAR(32) DEFAULT 'deepseek',
    default_model VARCHAR(64) DEFAULT 'deepseek-v4-flash',
    context_mode VARCHAR(16) DEFAULT 'server',
    daily_request_limit INT DEFAULT 100,
    daily_token_limit INT DEFAULT 100000,
    available_models TEXT DEFAULT 'deepseek-v4-flash,deepseek-v4-pro,gpt-4o',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sessions table
CREATE TABLE sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Chat messages table
CREATE TABLE chat_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls TEXT,
    tool_call_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

-- Quota usage table
CREATE TABLE quota_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    date DATE NOT NULL,
    request_count INT DEFAULT 0,
    token_count INT DEFAULT 0,
    CONSTRAINT uk_user_date UNIQUE (user_id, date),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Orders table (mock data)
CREATE TABLE orders (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    order_no VARCHAR(32) UNIQUE NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(10,2),
    created_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Logistics table (mock data)
CREATE TABLE logistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    location VARCHAR(128),
    eta VARCHAR(64),
    updated_at TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
