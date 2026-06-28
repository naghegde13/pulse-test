-- V60: RBAC & Auth tables

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(50) DEFAULT 'LOCAL';
ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE;

CREATE TABLE roles (
    id          VARCHAR(26) PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    permissions JSONB NOT NULL
);

CREATE TABLE user_roles (
    user_id     VARCHAR(26) NOT NULL REFERENCES users(id),
    role_id     VARCHAR(26) NOT NULL REFERENCES roles(id),
    tenant_id   VARCHAR(26) NOT NULL REFERENCES tenants(id),
    env_scope   VARCHAR(30),
    PRIMARY KEY (user_id, role_id, tenant_id)
);

-- Seed roles
INSERT INTO roles (id, name, permissions) VALUES
('01JROLE0CITIZEN000000001', 'CITIZEN', '["pipeline:read","pipeline:write","chat:use"]'),
('01JROLE0DATAENG000000001', 'DATA_ENGINEER', '["pipeline:read","pipeline:write","pipeline:deploy:dev","producer:write","chat:use","commands:view"]'),
('01JROLE0DEPLOYER00000001', 'DEPLOYER', '["pipeline:read","pipeline:deploy:int","pipeline:deploy:uat","pipeline:deploy:prod","pipeline:approve","chat:use","commands:view"]'),
('01JROLE0ADMIN00000000001', 'ADMIN', '["pipeline:read","pipeline:write","pipeline:deploy:dev","pipeline:deploy:int","pipeline:deploy:uat","pipeline:deploy:prod","pipeline:approve","producer:write","admin:users","admin:allowlist","chat:use","commands:view"]');

-- Update stub user with password (bcrypt of 'pulse-admin')
UPDATE users SET password_hash = '$2a$10$JMk6XGZm0ExWul.1NxwnKO0Cfj8Rulx6VnNflVAUnn8jeQFW5i.xG', active = TRUE WHERE id = '01JUSER00000000000000000';

-- Assign admin role
INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES
('01JUSER00000000000000000', '01JROLE0ADMIN00000000001', 'tenant-home-lending'),
('01JUSER00000000000000000', '01JROLE0ADMIN00000000001', 'tenant-unsecured-lending');

-- Demo users
INSERT INTO users (id, tenant_id, email, display_name, role, password_hash, active) VALUES
('01JUSER0CITIZEN000000001', 'tenant-home-lending', 'sarah@home-lending.com', 'Sarah Chen', 'CITIZEN', '$2a$10$JMk6XGZm0ExWul.1NxwnKO0Cfj8Rulx6VnNflVAUnn8jeQFW5i.xG', TRUE),
('01JUSER0DE0000000000001', 'tenant-home-lending', 'mike@home-lending.com', 'Mike Rivera', 'DATA_ENGINEER', '$2a$10$JMk6XGZm0ExWul.1NxwnKO0Cfj8Rulx6VnNflVAUnn8jeQFW5i.xG', TRUE),
('01JUSER0DEPLOYER0000001', 'tenant-home-lending', 'priya@home-lending.com', 'Priya Patel', 'DEPLOYER', '$2a$10$JMk6XGZm0ExWul.1NxwnKO0Cfj8Rulx6VnNflVAUnn8jeQFW5i.xG', TRUE);

INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES
('01JUSER0CITIZEN000000001', '01JROLE0CITIZEN000000001', 'tenant-home-lending'),
('01JUSER0DE0000000000001', '01JROLE0DATAENG000000001', 'tenant-home-lending'),
('01JUSER0DEPLOYER0000001', '01JROLE0DEPLOYER00000001', 'tenant-home-lending');
