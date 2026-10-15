-- Additive tables; existing application versions can ignore them.
CREATE TABLE ai_budget_control (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO ai_budget_control(id,enabled) VALUES (1,TRUE);
CREATE TABLE ai_daily_budget (
    budget_day DATE NOT NULL,
    scope VARCHAR(40) NOT NULL,
    reserved_microusd BIGINT NOT NULL DEFAULT 0 CHECK (reserved_microusd >= 0),
    calls INTEGER NOT NULL DEFAULT 0 CHECK (calls >= 0),
    PRIMARY KEY (budget_day,scope)
);
