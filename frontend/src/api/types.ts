// Shapes of the backend API (/api/v1). The server is the authority for every permission and rule.

export interface Me {
  userId: string;
  displayName: string;
  email: string;
  tenant: { id: string; name: string };
  roles: string[];
}

export interface Project {
  id: string;
  name: string;
  createdAt: string;
}

export interface ProjectList {
  items: Project[];
}

export interface FieldError {
  field: string;
  code: string;
}

export interface ErrorEnvelope {
  error: { code: string; message: string; correlationId: string | null; details?: FieldError[] };
}

export type RequirementKind = 'EXISTING_REPOSITORY' | 'NEW_PRODUCT';

export type RequirementState =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'MANUAL_INTAKE'
  | 'REPO_VALIDATING'
  | 'AWAITING_DOCUMENTS'
  | 'AWAITING_DOCUMENT_DECISION'
  | 'ANALYSING'
  | 'AWAITING_CLARIFICATION'
  | 'BRD_DRAFTED'
  | 'INTERNAL_REVIEW'
  | 'REVISION_REQUESTED'
  | 'INTERNAL_APPROVED'
  | 'SHARED_WITH_CLIENT'
  | 'CLIENT_CHANGES_REQUESTED'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELLED';

/** The eight PRD FR-REQ-01 fields plus the existing-repository / new-product choice. */
export interface RequirementContent {
  kind: RequirementKind;
  title: string;
  description: string | null;
  businessObjective: string | null;
  expectedOutcomes: string | null;
  acceptanceCriteria: string | null;
  repositoryUrl: string | null;
  constraints: string | null;
  integrations: string | null;
}

export interface RevisionSummary {
  revisionNo: number;
  contentHash: string;
  createdAt: string;
}

export interface RequirementDetail {
  id: string;
  key: string;
  projectId: string;
  state: RequirementState;
  version: number;
  currentRevisionNo: number;
  content: RequirementContent;
  revisions: RevisionSummary[];
  createdAt: string;
  updatedAt: string;
}

export interface RequirementRevision {
  requirementId: string;
  revisionNo: number;
  content: RequirementContent;
  contentHash: string;
  createdAt: string;
}

export interface RequirementSummary {
  id: string;
  key: string;
  title: string;
  state: RequirementState;
  projectId: string;
  currentRevisionNo: number;
  updatedAt: string;
}

export interface RequirementPage {
  items: RequirementSummary[];
  nextCursor: string | null;
}

export interface TimelineEntry {
  seq: number;
  fromState: RequirementState | null;
  toState: RequirementState;
  transition: string;
  occurredAt: string;
}

/** PRD section 6C document processing states. */
export type DocumentState =
  | 'UPLOADING'
  | 'QUARANTINED'
  | 'VALIDATING'
  | 'SCANNING'
  | 'READY_FOR_EXTRACTION'
  | 'EXTRACTING'
  | 'PROCESSED'
  | 'EXTRACTION_FAILED'
  | 'EXTRACTION_FAILED_FINAL'
  | 'UNSUPPORTED_CONTENT'
  | 'REJECTED_INVALID'
  | 'REJECTED_MALWARE'
  | 'UPLOAD_FAILED'
  | 'SUPERSEDED'
  | 'DELETED';

export interface DocumentItem {
  documentId: string;
  versionId: string;
  versionNo: number;
  fileName: string;
  type: 'PDF' | 'DOCX' | 'TXT' | 'MARKDOWN';
  sizeBytes: number;
  state: DocumentState;
  reasonCode: string | null;
  uploadedBy: string;
  uploadedAt: string;
  downloadable: boolean;
}

export interface DocumentList {
  items: DocumentItem[];
  limits: { maxFileBytes: number; maxFiles: number; maxTotalBytes: number };
}
