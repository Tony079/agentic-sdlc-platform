--liquibase formatted sql

--changeset sdlc:0015-document-extraction
-- Document processing output (PRD 7A.3, TDD 15.6). One row per extraction run of a document version; the text
-- itself is in ordered chunks that keep their version and position (FR-DPR-06). Written only by the worker after the
-- coordinator validated the isolated extractor's output; idempotent by unique keys (FR-DPR-08).
CREATE TABLE app.document_extraction (
  tenant_id       uuid        NOT NULL,
  version_id      uuid        NOT NULL,
  extraction_no   int         NOT NULL CHECK (extraction_no >= 1),
  outcome         text        NOT NULL CHECK (outcome IN ('PROCESSED', 'UNSUPPORTED_CONTENT', 'EXTRACTION_FAILED_FINAL')),
  reason_code     text        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  chunk_count     int         NOT NULL CHECK (chunk_count BETWEEN 0 AND 5000),
  char_count      int         NOT NULL CHECK (char_count BETWEEN 0 AND 2000000),
  output_sha256   text        CHECK (output_sha256 ~ '^[0-9a-f]{64}$'),
  extractor       text        NOT NULL CHECK (length(extractor) <= 64),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (version_id, extraction_no),
  UNIQUE (tenant_id, version_id, extraction_no),
  FOREIGN KEY (tenant_id, version_id) REFERENCES app.document_version (tenant_id, id),
  CHECK ((outcome = 'PROCESSED') = (output_sha256 IS NOT NULL))
);

-- Structured text search for the initial volume (FR-DPR-09): a 'simple' full-text index, no embeddings.
CREATE TABLE app.document_chunk (
  tenant_id     uuid        NOT NULL,
  version_id    uuid        NOT NULL,
  extraction_no int         NOT NULL,
  ordinal       int         NOT NULL CHECK (ordinal >= 1),
  label         text        CHECK (length(label) <= 200),
  page_no       int         CHECK (page_no >= 1),
  text          text        NOT NULL CHECK (length(text) BETWEEN 1 AND 4000),
  chunk_sha256  text        NOT NULL CHECK (chunk_sha256 ~ '^[0-9a-f]{64}$'),
  tsv           tsvector    GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, text)) STORED,
  PRIMARY KEY (version_id, extraction_no, ordinal),
  FOREIGN KEY (tenant_id, version_id, extraction_no)
    REFERENCES app.document_extraction (tenant_id, version_id, extraction_no)
);
CREATE INDEX document_chunk_tsv_ix ON app.document_chunk USING gin (tsv);

--changeset sdlc:0015-document-extraction-rls splitStatements:false
DO $rls$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['document_extraction', 'document_chunk'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I '
                   'USING (tenant_id = app.current_tenant()) WITH CHECK (tenant_id = app.current_tenant())', t);
  END LOOP;
END
$rls$;

--changeset sdlc:0015-document-extraction-grants
-- Worker only: append-only processing output. The API has no access (clients do not read extracted text in this
-- milestone; evidence views come with the BRD).
GRANT SELECT, INSERT ON app.document_extraction TO sdlc_worker;
GRANT INSERT (tenant_id, version_id, extraction_no, ordinal, label, page_no, text, chunk_sha256)
  ON app.document_chunk TO sdlc_worker;
GRANT SELECT ON app.document_chunk TO sdlc_worker;
