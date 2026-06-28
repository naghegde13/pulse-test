-- V73: Align seed domains and SOR domain assignments with dev state

-- Update Default domain description
UPDATE domains SET description = 'Domain to deal with Loan Default data'
WHERE id = '01JDOM0DEFAULT0000000001' AND tenant_id = 'tenant-home-lending';

-- Add Originations domain
INSERT INTO domains (id, tenant_id, name, description)
VALUES ('01JDOM0ORIGINATIONS0001', 'tenant-home-lending', 'Originations', 'This is the Mortgage Loan Origination Domain')
ON CONFLICT ON CONSTRAINT uq_domain_tenant_name DO NOTHING;

-- Move Loan Origination System from Servicing to Originations
UPDATE systems_of_record
SET domain_name = 'Originations'
WHERE id = '01JSOR0LOS0000000000001' AND tenant_id = 'tenant-home-lending';
