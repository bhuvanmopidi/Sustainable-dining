-- V1: operational schema (observable data only).
-- Units: carbon in kg CO2e, mass in grams. Simulator ground truth is never stored in this database.

CREATE TABLE experiment_config (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version     varchar(32) NOT NULL UNIQUE,
    body        jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dish (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   varchar(100)  NOT NULL UNIQUE,
    category               varchar(50)   NOT NULL,
    carbon_kg_per_serving  numeric(8,4)  NOT NULL CHECK (carbon_kg_per_serving >= 0),
    portion_mass_g         numeric(8,2)  NOT NULL CHECK (portion_mass_g > 0),
    eligible_by_default    boolean       NOT NULL,
    active                 boolean       NOT NULL DEFAULT true,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE semester (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                varchar(100) NOT NULL,
    mode                varchar(20)  NOT NULL CHECK (mode IN ('LIVE', 'SIMULATED_DEMO')),
    start_date          date         NOT NULL,
    enrolled_students   integer      NOT NULL CHECK (enrolled_students > 0),
    config_id           uuid         NOT NULL REFERENCES experiment_config(id),
    sim_seed            integer,
    sim_scenario        varchar(40),
    reference_model_id  uuid,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CHECK ((mode = 'SIMULATED_DEMO') = (sim_seed IS NOT NULL AND sim_scenario IS NOT NULL))
);

CREATE TABLE serving_slot (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    semester_id    uuid         NOT NULL REFERENCES semester(id) ON DELETE CASCADE,
    slot_index     integer      NOT NULL CHECK (slot_index >= 1),
    visibility     numeric(4,3) NOT NULL CHECK (visibility BETWEEN 0 AND 1),
    eligible_slot  boolean      NOT NULL,
    UNIQUE (semester_id, slot_index)
);

CREATE TABLE meal_period (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    semester_id          uuid        NOT NULL REFERENCES semester(id) ON DELETE CASCADE,
    meal_index           integer     NOT NULL CHECK (meal_index >= 0),
    meal_date            date        NOT NULL,
    meal_type            varchar(10) NOT NULL CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER')),
    phase                varchar(10) NOT NULL CHECK (phase IN ('BASELINE', 'WARMUP', 'ADAPTIVE')),
    expected_attendance  integer     NOT NULL CHECK (expected_attendance >= 0),
    state                varchar(12) NOT NULL DEFAULT 'PLANNED'
                         CHECK (state IN ('PLANNED', 'RECOMMENDED', 'DEPLOYED', 'OBSERVED', 'EVALUATED')),
    eval_status          varchar(30) CHECK (eval_status IN ('OK', 'EXCLUDED_ZERO_ATTENDANCE', 'REFERENCE_INCOMPLETE')),
    force_p0             boolean     NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (semester_id, meal_index),
    UNIQUE (semester_id, meal_date, meal_type)
);
CREATE INDEX idx_meal_period_semester_state ON meal_period (semester_id, state);

CREATE TABLE menu_item (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id                 uuid         NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    dish_id                 uuid         NOT NULL REFERENCES dish(id),
    role                    varchar(10)  NOT NULL CHECK (role IN ('ELIGIBLE', 'FIXED')),
    standard_slot           integer      NOT NULL CHECK (standard_slot >= 1),
    carbon_factor_snapshot  numeric(8,4) NOT NULL CHECK (carbon_factor_snapshot >= 0),
    portion_mass_snapshot   numeric(8,2) NOT NULL CHECK (portion_mass_snapshot > 0),
    carbon_score            numeric(7,6) NOT NULL CHECK (carbon_score BETWEEN 0 AND 1),
    UNIQUE (meal_id, dish_id),
    UNIQUE (meal_id, standard_slot)
);

CREATE TABLE candidate_layout (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id               uuid        NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    ordering              jsonb       NOT NULL,
    ordering_hash         char(64)    NOT NULL,
    generating_labels     jsonb       NOT NULL,
    d                     double precision NOT NULL,
    m                     double precision NOT NULL,
    phi                   jsonb       NOT NULL,
    pred_mean             double precision,
    bonus                 double precision,
    ucb_score             double precision,
    excluded_by_cooldown  boolean     NOT NULL,
    superseded            boolean     NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_candidate_active ON candidate_layout (meal_id, ordering_hash) WHERE NOT superseded;

CREATE TABLE reference_model (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    semester_id  uuid        NOT NULL UNIQUE REFERENCES semester(id) ON DELETE CASCADE,
    version      varchar(40) NOT NULL,
    body         jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE semester ADD CONSTRAINT fk_semester_reference
    FOREIGN KEY (reference_model_id) REFERENCES reference_model(id);

CREATE TABLE recommendation (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id             uuid        NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    candidate_id        uuid        NOT NULL REFERENCES candidate_layout(id),
    source              varchar(10) NOT NULL CHECK (source IN ('BASELINE', 'WARMUP', 'LEARNER', 'ROLLBACK')),
    reason_code         varchar(40) NOT NULL,
    config_id           uuid        NOT NULL REFERENCES experiment_config(id),
    reference_model_id  uuid        REFERENCES reference_model(id),
    learner_log_length  integer     NOT NULL CHECK (learner_log_length >= 0),
    superseded          boolean     NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_recommendation_active ON recommendation (meal_id) WHERE NOT superseded;

CREATE TABLE deployment (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id            uuid         NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    recommendation_id  uuid         NOT NULL REFERENCES recommendation(id),
    candidate_id       uuid         NOT NULL REFERENCES candidate_layout(id),
    deployed_by        varchar(100) NOT NULL,
    override_reason    text,
    superseded         boolean      NOT NULL DEFAULT false,
    created_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_deployment_active ON deployment (meal_id) WHERE NOT superseded;

CREATE TABLE observation (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id            uuid          NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    revision           integer       NOT NULL CHECK (revision >= 1),
    actual_attendance  integer       NOT NULL CHECK (actual_attendance >= 0),
    aggregate_waste_g  numeric(12,2) NOT NULL CHECK (aggregate_waste_g >= 0),
    source             varchar(10)   NOT NULL CHECK (source IN ('MANUAL', 'CSV', 'SIMULATED')),
    entered_by         varchar(100)  NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (meal_id, revision)
);

CREATE TABLE item_outcome (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    observation_id  uuid          NOT NULL REFERENCES observation(id) ON DELETE CASCADE,
    menu_item_id    uuid          NOT NULL REFERENCES menu_item(id),
    portions_taken  integer       NOT NULL CHECK (portions_taken >= 0),
    waste_g         numeric(10,2) CHECK (waste_g >= 0),
    UNIQUE (observation_id, menu_item_id)
);

CREATE TABLE meal_metrics (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id                      uuid    NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    observation_id               uuid    NOT NULL REFERENCES observation(id),
    carbon_per_attendee          double precision,
    waste_g_per_attendee         double precision,
    log_waste                    double precision,
    eligible_uptake              double precision,
    total_portions_per_attendee  double precision,
    c_ref                        double precision,
    w_ref                        double precision,
    uptake_ref                   double precision,
    z_c                          double precision,
    z_w                          double precision,
    reward_unclipped             double precision,
    reward                       double precision,
    learner_update               boolean NOT NULL,
    reference_model_id           uuid    REFERENCES reference_model(id),
    config_id                    uuid    NOT NULL REFERENCES experiment_config(id),
    superseded                   boolean NOT NULL DEFAULT false,
    created_at                   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_metrics_active ON meal_metrics (meal_id) WHERE NOT superseded;

CREATE TABLE safety_event (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id          uuid        NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    rule             varchar(20) NOT NULL CHECK (rule IN ('WASTE_SPIKE', 'WASTE_SUSTAINED', 'UPTAKE_DROP', 'UPTAKE_SUSTAINED')),
    observed_value   double precision NOT NULL,
    threshold        double precision NOT NULL,
    action           varchar(12) NOT NULL CHECK (action IN ('ROLLBACK', 'ALERT_ONLY')),
    cooldown_labels  jsonb       NOT NULL,
    acknowledged_by  varchar(100),
    acknowledged_at  timestamptz,
    superseded       boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cooldown (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    semester_id            uuid        NOT NULL REFERENCES semester(id) ON DELETE CASCADE,
    policy_label           varchar(2)  NOT NULL CHECK (policy_label IN ('P1', 'P2', 'P3')),
    meal_type              varchar(10) NOT NULL CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER')),
    remaining_occurrences  integer     NOT NULL CHECK (remaining_occurrences >= 0),
    source_event_id        uuid        REFERENCES safety_event(id),
    UNIQUE (semester_id, policy_label, meal_type)
);

CREATE TABLE forecast (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    meal_id            uuid        NOT NULL REFERENCES meal_period(id) ON DELETE CASCADE,
    menu_item_id       uuid        NOT NULL REFERENCES menu_item(id) ON DELETE CASCADE,
    predicted_per_100  double precision NOT NULL CHECK (predicted_per_100 >= 0),
    model_version      varchar(40) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (meal_id, menu_item_id, model_version)
);