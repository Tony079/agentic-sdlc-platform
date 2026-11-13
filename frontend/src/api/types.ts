// Shapes of the backend API (/api/v1). The server is the authority for every permission and rule.

export interface Me {
  userId: string;
  displayName: string;
  email: string;
  tenant: { id: string; name: string };
  roles: string[];
}

export type OrgRole = 'ORG_ADMIN' | 'MEMBER';
export type ProjectRole = 'REQUESTER' | 'APPROVER' | 'VIEWER';

export interface Project {
  id: string;
  name: string;
  description?: string | null;
  status?: 'ACTIVE' | 'ARCHIVED';
  /** The caller's project role, or null when not a member (an Organisation Admin sees every project). */
  myRole?: ProjectRole | null;
  memberCount?: number;
  createdAt: string;
  archivedAt?: string | null;
}

export interface ProjectMember {
  id: string;
  projectId: string;
  projectName: string;
  userId: string;
  displayName: string;
  email: string;
  userStatus: 'INVITED' | 'ACTIVE' | 'DISABLED';
  role: ProjectRole;
  addedAt: string;
}

export type LoginResult = Me | { status: 'MFA_REQUIRED' | 'MFA_ENROLMENT_REQUIRED' };

export interface MfaEnrolment {
  secret: string;
  otpauthUri: string;
}

export interface OrganisationSummary {
  id: string;
  name: string;
  slug: string;
  activeUsers: number;
  invitedUsers: number;
  deactivatedUsers: number;
  organisationAdmins: number;
  emailConfigured: boolean;
}

export interface Membership {
  memberId: string;
  projectId: string;
  projectName: string;
  role: ProjectRole;
}

export interface OrgUser {
  id: string;
  email: string;
  displayName: string;
  role: OrgRole;
  status: 'INVITED' | 'ACTIVE' | 'DISABLED';
  mfaEnrolled: boolean;
  createdAt: string;
  activatedAt: string | null;
  invitation: {
    id: string;
    purpose: 'INVITE' | 'PASSWORD_RESET';
    createdAt: string;
    expiresAt: string;
    emailStatus: 'NOT_SENT' | 'SENT' | 'FAILED';
  } | null;
  memberships: Membership[];
}

export interface LinkResult {
  userId: string;
  invitationId: string;
  link: string;
  emailStatus: 'NOT_SENT' | 'SENT' | 'FAILED';
  expiresAt: string;
}

export interface AccountLinkDetails {
  organisationName: string;
  organisationSlug: string;
  email: string;
  displayName: string;
  purpose: 'INVITE' | 'PASSWORD_RESET';
  expiresAt: string;
}

// Garuda staff console

export interface DashboardRequirement {
  organisationId: string;
  organisationName: string;
  projectId: string;
  projectName: string;
  key: string;
  title: string;
  state: string;
  createdAt: string;
  stateSince: string;
  daysInState: number;
  bottleneck: 'FAILED' | 'WAITING_FOR_CLIENT' | 'WAITING_FOR_REVIEW' | 'WAITING_FOR_RELEASE' | 'WAITING_FOR_INTAKE' | null;
}

export interface DashboardOrganisation {
  id: string;
  name: string;
  slug: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt: string;
  activeUsers: number;
  invitedUsers: number;
  projects: number;
  repositories: number;
  requirements: number;
  openRequirements: number;
  bottlenecks: number;
  lastActivityAt: string | null;
  admins: { displayName: string; email: string; status: string }[];
}

export interface DashboardOverview {
  organisations: number;
  activeOrganisations: number;
  suspendedOrganisations: number;
  users: number;
  projects: number;
  repositories: number;
  requirements: number;
  requirementsByState: Record<string, number>;
  organisationRows: DashboardOrganisation[];
  bottlenecks: DashboardRequirement[];
  staleDays: number;
}

export interface OrganisationDetail {
  organisation: DashboardOrganisation;
  projects: { id: string; name: string; status: string; members: number; requirements: number }[];
  requirements: DashboardRequirement[];
}

export interface OnboardingRequest {
  id: string;
  organisationName: string;
  organisationSlug: string;
  adminEmail: string;
  adminDisplayName: string;
  status: 'REQUESTED' | 'EXECUTED' | 'REJECTED' | 'CANCELLED';
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
  approvedBy: string | null;
  approvedByName: string | null;
  executedAt: string | null;
  createdTenantId: string | null;
  decisionNote: string | null;
}

export interface OnboardingApproved {
  request: OnboardingRequest;
  invitationLink: string;
  emailStatus: 'NOT_SENT' | 'SENT' | 'FAILED';
}

export interface OrganisationStatusRequest {
  id: string;
  tenantId: string;
  organisationName: string;
  kind: 'SUSPEND' | 'REACTIVATE';
  status: 'REQUESTED' | 'EXECUTED' | 'REJECTED' | 'CANCELLED';
  reason: string;
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
  approvedBy: string | null;
  executedAt: string | null;
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
  /** Restricted requirements are visible to their owner and the project's Approvers only (GD-05). */
  restricted?: boolean;
  ownedByMe?: boolean;
  myRole?: ProjectRole | null;
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

// R1-M4: repository link requests (C-18), registration (C-01) and staff sessions (O-06, O-11).

export interface IssuedLinkRequest {
  requestId: string;
  token: string;
  expiresAt: string;
}

export interface RepositoryLinkStatus {
  pendingRequest: { requestId: string; expiresAt: string } | null;
  link: { linkId: string; repositoryUrl: string; status: 'AUTHORIZED' | 'REGISTERED'; authorizedAt: string } | null;
  validation: {
    status: 'PENDING_VALIDATION' | 'VALIDATED' | 'FAILED';
    pinnedSha: string | null;
    defaultBranch: string | null;
    failureCode: string | null;
    snapshotStatus: 'STORED' | 'INDEXED' | 'FAILED' | null;
    fileCount: number | null;
  } | null;
}

export interface RedeemedLink {
  linkId: string;
  status: string;
}

export interface StaffMe {
  staffId: string;
  email: string;
  displayName: string;
  activeTenant: { id: string; name: string } | null;
  assignedTenants: { id: string; name: string }[];
  capabilities: string[];
}

export interface PendingRepositoryLink {
  linkId: string;
  repositoryUrl: string;
  projectId: string;
  authorizedAt: string;
}

export interface RepositorySummary {
  id: string;
  owner: string;
  name: string;
  repositoryUrl: string;
  status: 'PENDING_VERIFICATION' | 'VERIFIED' | 'UNAVAILABLE' | 'ACCESS_DENIED';
  defaultBranch: string | null;
  registeredAt: string;
  verifiedAt: string | null;
}

// R1-M5/M6: BRD versions, review, client view, notifications.

export interface BrdEvidence {
  type: 'REPOSITORY' | 'DOCUMENT';
  path: string | null;
  startLine: number | null;
  endLine: number | null;
  commitSha: string | null;
  documentVersionId: string | null;
  fileName: string | null;
  chunk: number | null;
  chunkLabel: string | null;
  quote: string | null;
  supportPassed: boolean | null;
}

export interface BrdItem {
  itemKey: string;
  ordinal: number;
  kind: 'FACT' | 'ASSUMPTION' | 'RECOMMENDATION' | 'QUESTION';
  proposedKind: string;
  section: string;
  statement: string;
  risk: string | null;
  component: string | null;
  supportState: 'VERIFIED' | 'FLAGGED' | 'UNSUPPORTED';
  rejectedCitations: number;
  acknowledged: boolean;
  evidence: BrdEvidence[];
}

export interface BrdVersion {
  id: string;
  requirementId: string;
  versionNo: number;
  status: string;
  contentHash: string;
  inputHash: string;
  understanding: string | null;
  excludedDocuments: string;
  modelProvider: string;
  modelId: string;
  createdAt: string;
  items: BrdItem[];
}

export interface ClientBrd {
  requirementId: string;
  state: string;
  version: BrdVersion;
}

export interface ReviewQueueEntry {
  requirementId: string;
  requirementKey: string;
  title: string;
  brdVersionId: string;
  versionNo: number;
  flagged: number;
  unacknowledged: number;
  createdAt: string;
}

export interface ReviewDetail {
  requirementKey: string;
  requirement: RequirementContent;
  state: string;
  version: BrdVersion;
  versions?: VersionRef[];
}

export interface VersionRef {
  id: string;
  versionNo: number;
  status: string;
  contentHash: string;
}

export interface ChangedItem {
  itemKey: string;
  before: BrdItem;
  after: BrdItem;
  fields: string[];
}

export interface VersionDiff {
  from: VersionRef;
  to: VersionRef;
  added: BrdItem[];
  removed: BrdItem[];
  changed: ChangedItem[];
  unchanged: number;
}

export interface ClarificationMessage {
  kind: string;
  authorType: string;
  text: string;
  createdAt: string;
}

export interface Clarification {
  id: string;
  requirementId: string;
  source: string;
  blocking: boolean;
  assignedRole: string;
  status: string;
  question: string;
  whyItMatters: string | null;
  interpretations: string | null;
  recommendedOption: string | null;
  createdAt: string;
  messages: ClarificationMessage[];
}

export interface PendingDocumentDecision {
  requirementId: string;
  requirementKey: string;
  documentId: string;
  versionId: string;
  fileName: string;
  state: string;
  reasonCode: string | null;
}

export interface NotificationItem {
  id: string;
  kind: string;
  requirementId: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface ScopeDiffItem {
  field: string;
  area: string;
  changeType: 'ADDITION' | 'MODIFICATION' | 'REMOVAL';
  recommendedPriority: string;
  priority: string;
  before: string | null;
  after: string | null;
  adjustmentReason: string | null;
}

export interface ChangeRequest {
  id: string;
  requirementId: string;
  status: string;
  requestedChange: string;
  justification: string;
  expectedImpact: string;
  submittedAt: string;
  scopeAssessment: string | null;
  effortEstimate: string | null;
  timelineImpact: string | null;
  technicalImpact: string | null;
  reviewNote: string | null;
  decisionNote: string | null;
  baselineVersion: number;
  diff: ScopeDiffItem[];
}

export interface TenantLifecycleRequest {
  id: string;
  tenantId: string;
  tenantName: string;
  kind: 'DELETE' | 'RESTORE';
  status: string;
  reason: string;
  requestedAt: string;
}
