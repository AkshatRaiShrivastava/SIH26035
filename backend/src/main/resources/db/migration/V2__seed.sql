INSERT INTO roles(name, description) VALUES
    ('ADMIN', 'Platform administrator'),
    ('TEST_ENGINEER', 'Laboratory test engineer'),
    ('REVIEWER', 'Report reviewer'),
    ('VIEWER', 'Read-only report viewer')
ON CONFLICT (name) DO NOTHING;

INSERT INTO users(username, email, password_hash, first_name, is_active) VALUES
    ('engineer', 'engineer@demo.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Demo Engineer', TRUE),
    ('reviewer', 'reviewer@demo.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Demo Reviewer', TRUE),
    ('admin', 'admin@demo.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldLZdL17lhWy', 'Demo Admin', TRUE)
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'engineer@demo.local' AND r.name = 'TEST_ENGINEER'
ON CONFLICT DO NOTHING;

INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'reviewer@demo.local' AND r.name = 'REVIEWER'
ON CONFLICT DO NOTHING;

INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'admin@demo.local' AND r.name = 'ADMIN'
ON CONFLICT DO NOTHING;
