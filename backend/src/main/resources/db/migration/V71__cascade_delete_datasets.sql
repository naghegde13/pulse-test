ALTER TABLE datasets DROP CONSTRAINT datasets_sor_id_fkey;
ALTER TABLE datasets ADD CONSTRAINT datasets_sor_id_fkey
    FOREIGN KEY (sor_id) REFERENCES systems_of_record(id) ON DELETE CASCADE;

ALTER TABLE datasets DROP CONSTRAINT datasets_connector_instance_id_fkey;
ALTER TABLE datasets ADD CONSTRAINT datasets_connector_instance_id_fkey
    FOREIGN KEY (connector_instance_id) REFERENCES connector_instances(id) ON DELETE CASCADE;
