CREATE TABLE test_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    test_type VARCHAR(80) NOT NULL,
    rule_standard VARCHAR(120) NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    result VARCHAR(10),
    test_load NUMERIC(18,6) NOT NULL,
    indication NUMERIC(18,6) NOT NULL,
    error_value NUMERIC(18,6),
    permissible_error NUMERIC(18,6),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE report_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    test_run_id UUID NOT NULL REFERENCES test_runs(id),
    version INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    canonical_data TEXT NOT NULL,
    sha256_hash VARCHAR(64),
    finalized_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(test_run_id, version)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(160) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    payload TEXT NOT NULL,
    previous_hash VARCHAR(64),
    entry_hash VARCHAR(64) NOT NULL UNIQUE
);

CREATE UNIQUE INDEX uq_manufacturers_name ON manufacturers(name);
CREATE UNIQUE INDEX uq_instrument_models_number ON instrument_models(model_number);
CREATE UNIQUE INDEX uq_instruments_serial_number ON instruments(serial_number);

INSERT INTO manufacturers(name) VALUES ('ABC Weighing Systems'), ('XYZ Instruments') ON CONFLICT DO NOTHING;
INSERT INTO instrument_models(manufacturer_id, model_number, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type)
SELECT m.id, 'ABC-1000', 'III', 1000, 1, 1, 'kg', 'ELECTRONIC' FROM manufacturers m WHERE m.name = 'ABC Weighing Systems'
ON CONFLICT DO NOTHING;
INSERT INTO instruments(instrument_model_id, manufacturer_id, serial_number, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type)
SELECT im.id, im.manufacturer_id, 'SN-ABC-1000-01', 'III', 1000, 1, 1, 'kg', 'ELECTRONIC' FROM instrument_models im WHERE im.model_number = 'ABC-1000'
ON CONFLICT (serial_number) DO NOTHING;
INSERT INTO instrument_models(manufacturer_id, model_number, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type)
SELECT m.id, 'XYZ-500', 'III', 500, 1, 1, 'kg', 'ELECTRONIC' FROM manufacturers m WHERE m.name = 'XYZ Instruments'
ON CONFLICT DO NOTHING;
INSERT INTO instruments(instrument_model_id, manufacturer_id, serial_number, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type)
SELECT im.id, im.manufacturer_id, 'SN-XYZ-500-07', 'III', 500, 1, 1, 'kg', 'ELECTRONIC' FROM instrument_models im WHERE im.model_number = 'XYZ-500'
ON CONFLICT (serial_number) DO NOTHING;