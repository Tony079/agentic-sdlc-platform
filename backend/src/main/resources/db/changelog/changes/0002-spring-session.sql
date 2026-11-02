--liquibase formatted sql

--changeset sdlc:0002-spring-session
-- Spring Session JDBC 4.1 schema (spring-session-jdbc schema-postgresql.sql), in schema app.
-- Global tables: sessions are created before a tenant is known. They hold the serialized security context only.
CREATE TABLE app.spring_session (
  primary_id            char(36)     NOT NULL,
  session_id            char(36)     NOT NULL,
  creation_time         bigint       NOT NULL,
  last_access_time      bigint       NOT NULL,
  max_inactive_interval int          NOT NULL,
  expiry_time           bigint       NOT NULL,
  principal_name        varchar(100),
  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON app.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON app.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON app.spring_session (principal_name);

CREATE TABLE app.spring_session_attributes (
  session_primary_id char(36)     NOT NULL,
  attribute_name     varchar(200) NOT NULL,
  attribute_bytes    bytea        NOT NULL,
  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
    REFERENCES app.spring_session (primary_id) ON DELETE CASCADE
);
