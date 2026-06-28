-- V64: Domains as a first-class entity belonging to Tenant
CREATE TABLE domains (
    id          VARCHAR(26) PRIMARY KEY,
    tenant_id   VARCHAR(26) NOT NULL REFERENCES tenants(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_domain_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_domains_tenant ON domains(tenant_id);

-- Seed default domains for tenant-home-lending
INSERT INTO domains (id, tenant_id, name, description) VALUES
('01JDOM0SERVICING00000001', 'tenant-home-lending', 'Servicing', 'Loan servicing and payment processing'),
('01JDOM0CAPMKTS0000000001', 'tenant-home-lending', 'Capital Markets', 'Investor reporting and secondary market operations'),
('01JDOM0DEFAULT0000000001', 'tenant-home-lending', 'Default', 'General purpose / unclassified');
