-- V3: corrections. A corrected observation is a new revision and must say why; the first revision has no reason.
ALTER TABLE observation ADD COLUMN correction_reason text;
ALTER TABLE observation ADD CONSTRAINT chk_observation_correction_reason
    CHECK ((revision = 1) = (correction_reason IS NULL));