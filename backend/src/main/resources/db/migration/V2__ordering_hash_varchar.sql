-- char(64) is reported as bpchar, which Hibernate maps awkwardly; varchar(64) holds the same sha256 hex.
ALTER TABLE candidate_layout ALTER COLUMN ordering_hash TYPE varchar(64);