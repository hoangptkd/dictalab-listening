-- Additive: password login and all existing learning data remain intact.
ALTER TABLE app_users ADD COLUMN google_subject VARCHAR(255);
CREATE UNIQUE INDEX uk_app_users_google_subject ON app_users(google_subject);
