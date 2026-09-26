-- Run after Flyway against a disposable database. It exercises the generated n value
-- and constraint boundaries used by the Phase 1 acceptance test.
BEGIN;
INSERT INTO manufacturer(id,legal_name) VALUES ('10000000-0000-0000-0000-000000000001','Demo Weights Ltd');
INSERT INTO app_user(id,full_name,email,password_hash) VALUES ('20000000-0000-0000-0000-000000000001','Demo Engineer','engineer@example.test','not-for-production');
INSERT INTO instrument(id,manufacturer_id,model_no,serial_no,accuracy_class_id,max_capacity,min_capacity,e_value,d_value) VALUES ('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','ABC-1000','DEMO-001',3,1000,1,1,0.1);
DO $$ BEGIN IF (SELECT n_intervals FROM instrument WHERE id='30000000-0000-0000-0000-000000000001') <> 1000 THEN RAISE EXCEPTION 'generated n_intervals failed'; END IF; END $$;
INSERT INTO test_session(id,instrument_id,engineer_id,test_type,rule_version_id) VALUES ('40000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','INITIAL_VERIFICATION','00000000-0000-0000-0000-000000000076');
INSERT INTO observation(test_session_id,sequence_no,test_procedure,applied_load,indicated_value,recorded_by) VALUES ('40000000-0000-0000-0000-000000000001',1,'WEIGHING_TEST',250,250.1,'20000000-0000-0000-0000-000000000001'),('40000000-0000-0000-0000-000000000001',2,'WEIGHING_TEST',500,500.2,'20000000-0000-0000-0000-000000000001'),('40000000-0000-0000-0000-000000000001',3,'WEIGHING_TEST',1000,1000.3,'20000000-0000-0000-0000-000000000001');
ROLLBACK;
