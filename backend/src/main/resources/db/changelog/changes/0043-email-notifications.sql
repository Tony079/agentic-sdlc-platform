--liquibase formatted sql

-- GD-25 (owner, 2026-11-17): in-app notifications are also sent by email (supersedes AC-EXT-40 "in-app only").
-- The API process delivers them: only it may read members' email addresses. An email carries the requirement key and
-- title and a link, never BRD or clarification content. Each member can turn email off.

--changeset sdlc:0043-notification-email
ALTER TABLE app.notification
  ADD COLUMN email_status   text NOT NULL DEFAULT 'PENDING'
    CHECK (email_status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
  ADD COLUMN email_attempts int  NOT NULL DEFAULT 0 CHECK (email_attempts BETWEEN 0 AND 10),
  ADD COLUMN emailed_at     timestamptz;
-- Notifications from before this change are not emailed.
UPDATE app.notification SET email_status = 'SKIPPED';
CREATE INDEX notification_email_pending_ix ON app.notification (tenant_id, created_at) WHERE email_status = 'PENDING';
GRANT UPDATE (email_status, email_attempts, emailed_at) ON app.notification TO sdlc_app;

--changeset sdlc:0043-user-email-preference
ALTER TABLE app.app_user ADD COLUMN email_notifications boolean NOT NULL DEFAULT true;
GRANT UPDATE (email_notifications) ON app.app_user TO sdlc_app;
