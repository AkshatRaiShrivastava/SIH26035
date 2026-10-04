CREATE TABLE test_observations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    test_run_id UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL,
    test_load NUMERIC(18,6) NOT NULL,
    indication NUMERIC(18,6) NOT NULL,
    scale_interval NUMERIC(18,6) NOT NULL,
    error_value NUMERIC(18,6) NOT NULL,
    permissible_error NUMERIC(18,6) NOT NULL,
    result VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(test_run_id, sequence_no)
);