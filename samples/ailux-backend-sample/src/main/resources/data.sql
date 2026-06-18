-- Preset users
INSERT INTO users (id, name, token, default_provider, default_model, context_mode, daily_request_limit, daily_token_limit, available_models) VALUES
('user-001', 'free_user',  'token-free-001',  'deepseek', 'deepseek-v4-flash', 'server',  20,  10000,  'deepseek-v4-flash'),
('user-002', 'pro_user',   'token-pro-001',   'deepseek', 'deepseek-v4-flash', 'server', 100, 100000, 'deepseek-v4-flash,deepseek-v4-pro,gpt-4o'),
('user-003', 'admin',      'token-admin-001', 'deepseek', 'deepseek-v4-flash', 'server',  -1,     -1, 'deepseek-v4-flash,deepseek-v4-pro,gpt-4o');

-- Mock orders for pro_user
INSERT INTO orders (id, user_id, order_no, item_name, status, amount, created_at) VALUES
('ord-001', 'user-002', 'ORD-2026-001', '机械键盘 Cherry MX Blue', 'shipped', 599.00, TIMESTAMP '2026-06-01 10:30:00'),
('ord-002', 'user-002', 'ORD-2026-002', 'Sony WH-1000XM5 降噪耳机', 'delivered', 2299.00, TIMESTAMP '2026-05-20 14:00:00'),
('ord-003', 'user-002', 'ORD-2026-003', 'MacBook Pro 保护壳', 'pending', 129.00, TIMESTAMP '2026-06-08 09:15:00'),
('ord-004', 'user-002', 'ORD-2026-004', 'USB-C 扩展坞', 'shipped', 359.00, TIMESTAMP '2026-06-05 16:45:00');

-- Mock orders for free_user
INSERT INTO orders (id, user_id, order_no, item_name, status, amount, created_at) VALUES
('ord-005', 'user-001', 'ORD-2026-005', '手机壳 iPhone 15', 'delivered', 49.00, TIMESTAMP '2026-05-25 11:00:00'),
('ord-006', 'user-001', 'ORD-2026-006', 'Type-C 数据线 2m', 'shipped', 29.00, TIMESTAMP '2026-06-06 08:30:00');

-- Mock logistics
INSERT INTO logistics (order_id, status, location, eta, updated_at) VALUES
('ord-001', 'in_transit', '深圳中转站', '明天下午', TIMESTAMP '2026-06-08 18:00:00'),
('ord-002', 'delivered', '已签收 - 丰巢柜 A12', NULL, TIMESTAMP '2026-05-23 10:30:00'),
('ord-004', 'out_for_delivery', '广州天河区配送中', '今天下午 3 点前', TIMESTAMP '2026-06-09 08:00:00'),
('ord-006', 'in_transit', '武汉分拨中心', '后天', TIMESTAMP '2026-06-08 22:00:00');
