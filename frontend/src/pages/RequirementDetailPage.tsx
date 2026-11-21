import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../api/client';
import type {
  RequirementContent,
  RequirementDetail,
  RequirementRevision,
  RequirementState,
  TimelineEntry,
} from '../api/types';
import { fieldMessage } from '../components/RequirementForm';
import { DocumentsPanel } from '../components/DocumentsPanel';
import { BrdPanel } from '../components/BrdPanel';
import { DesignPanel } from '../components/DesignPanel';
import { EstimatePanel } from '../components/EstimatePanel';
import { PlanPanel } from '../components/PlanPanel';
import { DevelopmentPanel } from '../components/DevelopmentPanel';
import { RepositoryLinkPanel } from '../components/RepositoryLinkPanel';
import { SourcesPanel } from '../components/SourcesPanel';
import { AnalysisRetryPanel } from '../components/AnalysisRetryPanel';
import { BrdQuotePanel } from '../components/BrdQuotePanel';
import { Icon } from '../ui/Icon';
import {
  BackLink,
  Badge,
  Card,
  Loading,
  Modal,
  PageHeader,
  RoleBadges,
  StatePill,
  Tabs,
  formatDateTime,
  relativeTime,
  stateLabel,
} from '../ui/components';

const CONTENT_LABELS: [keyof RequirementContent, string][] = [
  ['description', 'Description'],
  ['businessObjective', 'Business objective'],
  ['expectedOutcomes', 'Expected outcomes'],
  ['acceptanceCriteria', 'Acceptance criteria'],
  ['repositoryUrl', 'Repository URL'],
  ['constraints', 'Constraints'],
  ['integrations', 'Integrations'],
];

function ContentView({ content }: { content: RequirementContent }) {
  return (
    <dl className="content">
      <dt>Type</dt>
      <dd>{content.kind === 'NEW_PRODUCT' ? 'New product' : 'Enhancement to an existing product'}</dd>
      {CONTENT_LABELS.filter(([field]) => field !== 'repositoryUrl' || Boolean(content.repositoryUrl)).map(
        ([field, label]) => (
          <div key={field}>
            <dt>{label}</dt>
            <dd>{content[field] ?? <em>Not provided</em>}</dd>
          </div>
        ),
      )}
    </dl>
  );
}

/** What each state reached so far means for the client (PRD section 6). */
const STATE_NOTES: Partial<Record<RequirementState, string>> = {
  SUBMITTED: 'Submitted. The platform is routing the requirement; this page updates automatically.',
  REPO_VALIDATING:
    'Preparing the inputs: Garuda pins the code of each repository to analyse (a new product has none). The analysis starts on its own.',
  MANUAL_INTAKE:
    'New-product request kept for manual intake by the platform team. No automated analysis is scheduled.',
};

/** States in which the owner may cancel (PRD section 6: any non-terminal state; these are the ones reachable so far). */
const CANCELLABLE: RequirementState[] = [
  'DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION', 'FAILED',
  'ANALYSING', 'BRD_DRAFTED', 'INTERNAL_REVIEW', 'REVISION_REQUESTED', 'INTERNAL_APPROVED', 'SHARED_WITH_CLIENT',
  'CLIENT_CHANGES_REQUESTED',
];

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'VERSION_CHANGED':
        return 'This requirement was changed elsewhere. Reload to see the latest version.';
      case 'ILLEGAL_TRANSITION':
        return 'This action is not allowed in the current state.';
      case 'VALIDATION_FAILED':
        return error.details.length > 0
          ? `Cannot submit yet: ${error.details.map((d) => `${d.field} – ${fieldMessage(d.field, d.code)}`).join(' ')}`
          : 'The request is invalid.';
    }
  }
  return 'The action failed.';
}

/** States in which the worker moves the requirement on without user action. */
/** The requirement text starts open while it is being written or prepared; afterwards the BRD is the focus. */
const EARLY_STATES = new Set(['DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS',
  'AWAITING_DOCUMENT_DECISION']);

const ACTIVE_STATES = new Set(['SUBMITTED', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'ANALYSING', 'BRD_DRAFTED',
  'REVISION_REQUESTED', 'CLIENT_CHANGES_REQUESTED', 'INTERNAL_APPROVED']);

type Stage = 'brd' | 'design' | 'estimate' | 'plan' | 'development';
const STAGES: Stage[] = ['brd', 'design', 'estimate', 'plan', 'development'];
const STAGE_LABELS: Record<Stage, string> = {
  brd: 'BRD', design: 'Technical design', estimate: 'Estimate', plan: 'Delivery plan', development: 'Development',
};

export function RequirementDetailPage() {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [viewedRevision, setViewedRevision] = useState<RequirementRevision | null>(null);
  const [busy, setBusy] = useState(false);
  // The stage shown: the current one unless the user picked another (kept in the URL so reloads and links keep it).
  const [search, setSearch] = useSearchParams();

  const requirement = useQuery({
    queryKey: ['requirement', id],
    queryFn: () => api.get<RequirementDetail>(`/api/v1/requirements/${id}`),
    // Work happens asynchronously in the worker: poll while the requirement is moving on its own.
    refetchInterval: (query) => (ACTIVE_STATES.has(query.state.data?.state ?? '') ? 4000 : false),
  });
  const timeline = useQuery({
    queryKey: ['requirement', id, 'timeline'],
    queryFn: () => api.get<TimelineEntry[]>(`/api/v1/requirements/${id}/timeline`),
    refetchInterval: ACTIVE_STATES.has(requirement.data?.state ?? '') ? 4000 : false,
  });

  // GD-27: after BRD approval the header shows the technical design status (shared with the design panel's query).
  const design = useQuery({
    queryKey: ['design', id],
    queryFn: () => api.get<{ review: { stage: string } }>(`/api/v1/requirements/${id}/design`),
    enabled: requirement.data?.state === 'SHARED_WITH_CLIENT',
  });
  // GD-33 / GD-34: the same for estimate and delivery plan (queries shared with their panels).
  const estimate = useQuery({
    queryKey: ['estimate', id],
    queryFn: () => api.get<{ review: { stage: string } }>(`/api/v1/requirements/${id}/estimate`),
    enabled: design.data?.review.stage === 'APPROVED',
  });
  const plan = useQuery({
    queryKey: ['plan', id],
    queryFn: () => api.get<{ review: { stage: string } }>(`/api/v1/requirements/${id}/plan`),
    enabled: estimate.data?.review.stage === 'APPROVED',
  });
  // GD-36: development is the current stage once a run exists (shared with the development panel's query); until
  // then the plan stage stays current, since the plan is pushed from there.
  const development = useQuery({
    queryKey: ['development', id],
    queryFn: () => api.get<{ run: unknown | null }>(`/api/v1/requirements/${id}/development`),
    enabled: plan.data?.review.stage === 'APPROVED',
  });
  const started = (stage: string | undefined) =>
    stage === undefined ? undefined : ['GENERATING', 'IN_REVIEW', 'APPROVED', 'FAILED'].includes(stage) ? stage : null;
  const designStage = design.data?.review.stage;
  const designStatus = designStage === undefined || designStage === 'NOT_STARTED' || designStage === 'BRD_NOT_APPROVED'
    ? null : designStage;

  const designApproved = designStage === 'APPROVED';
  const estimateApproved = estimate.data?.review.stage === 'APPROVED';
  const planApproved = plan.data?.review.stage === 'APPROVED';
  const developing = planApproved && !!development.data?.run;
  const currentStage: Stage = developing ? 'development' : estimateApproved ? 'plan' : designApproved ? 'estimate'
    : requirement.data?.state === 'SHARED_WITH_CLIENT' ? 'design' : 'brd';
  const reachable: Record<Stage, boolean> = {
    brd: true, design: requirement.data?.state === 'SHARED_WITH_CLIENT', estimate: designApproved,
    plan: estimateApproved, development: planApproved,
  };
  const picked = search.get('stage') as Stage | null;
  const stage: Stage = picked && STAGES.includes(picked) && reachable[picked] ? picked : currentStage;
  const done: Record<Stage, boolean> = {
    brd: reachable.design, design: designApproved, estimate: estimateApproved, plan: planApproved, development: false,
  };
  const pickStage = (next: Stage) => {
    const params = new URLSearchParams(search);
    if (next === currentStage) {
      params.delete('stage');
    } else {
      params.set('stage', next);
    }
    setSearch(params, { replace: true });
  };

  if (requirement.isPending) {
    return <Loading />;
  }
  if (requirement.isError) {
    return (
      <p role="alert" className="error">
        {requirement.error instanceof ApiError && requirement.error.status === 404
          ? 'Requirement not found.'
          : 'The requirement could not be loaded.'}
      </p>
    );
  }
  const detail = requirement.data;
  const editable = detail.state === 'DRAFT' || detail.state === 'SUBMITTED';
  const cancellable = CANCELLABLE.includes(detail.state);
  const note = STATE_NOTES[detail.state];

  async function run(action: () => Promise<RequirementDetail>) {
    setBusy(true);
    setActionError(null);
    try {
      const updated = await action();
      queryClient.setQueryData(['requirement', id], updated);
      await queryClient.invalidateQueries({ queryKey: ['requirement', id, 'timeline'] });
      await queryClient.invalidateQueries({ queryKey: ['requirements'] });
      // Submission opens the BRD quote and the analysed sources.
      await queryClient.invalidateQueries({ queryKey: ['brd-quote', id] });
      await queryClient.invalidateQueries({ queryKey: ['sources', id] });
      setCancelling(false);
      setReason('');
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  function submit() {
    if (window.confirm(`Submit ${detail.key}?`)) {
      void run(() => api.post<RequirementDetail>(`/api/v1/requirements/${detail.id}/submit`, undefined, ifMatch(detail.version)));
    }
  }

  function cancel(event: FormEvent) {
    event.preventDefault();
    void run(() =>
      api.post<RequirementDetail>(`/api/v1/requirements/${detail.id}/cancel`, { reason }, ifMatch(detail.version)),
    );
  }

  async function viewRevision(revisionNo: number) {
    setViewedRevision(await api.get<RequirementRevision>(`/api/v1/requirements/${detail.id}/revisions/${revisionNo}`));
  }

  async function toggleRestricted() {
    const next = !detail.restricted;
    const text = next
      ? 'Restrict this requirement? Only you and the project Approvers will see it.'
      : 'Open this requirement to every member of the project?';
    if (!window.confirm(text)) return;
    setBusy(true);
    setActionError(null);
    try {
      await api.put(`/api/v1/requirements/${detail.id}/restricted`, { restricted: next });
      await queryClient.invalidateQueries({ queryKey: ['requirement', id] });
    } catch {
      setActionError('The visibility could not be changed.');
    } finally {
      setBusy(false);
    }
  }

  const owner = detail.ownedByMe !== false;
  const canRestrict = owner || !!detail.myRoles?.includes('BRD_APPROVER');
  return (
    <>
      <BackLink to="/requirements">All requirements</BackLink>
      <PageHeader
        eyebrow={<span className="mono">{detail.key}</span>}
        title={detail.content.title}
        description={
          <span className="row">
            <span className="sr-only">State:</span>
            <StatePill state={detail.state} designStatus={design.isSuccess ? designStatus : undefined}
              estimateStatus={estimate.isSuccess ? started(estimate.data.review.stage) : undefined}
              planStatus={plan.isSuccess ? started(plan.data.review.stage) : undefined} />
            <span className="small">Revision {detail.currentRevisionNo}</span>
            {detail.restricted && (
              <Badge tone="warning">
                <Icon name="lock" size={12} /> Restricted
              </Badge>
            )}
            {!owner && <Badge tone="neutral">Read only</Badge>}
          </span>
        }
        actions={
          owner && (
            <>
              {editable && (
                <Link to={`/requirements/${detail.id}/edit`} className="btn btn-secondary">
                  Edit
                </Link>
              )}
              {cancellable && !cancelling && (
                <button type="button" className="btn-secondary" onClick={() => setCancelling(true)} disabled={busy}>
                  Cancel requirement
                </button>
              )}
              {detail.state === 'DRAFT' && (
                <button type="button" className="btn-primary" onClick={submit} disabled={busy}>
                  Submit
                </button>
              )}
            </>
          )
        }
      />
      {note && (
        <p className="notice" role="status">
          {note}
        </p>
      )}
      {actionError && (
        <p role="alert" className="error">
          {actionError}{' '}
          <button type="button" className="btn-sm" onClick={() => requirement.refetch()}>
            Reload
          </button>
        </p>
      )}

      {cancelling && (
        <div className="card">
          <form onSubmit={cancel} className="cancel-form">
            <label>
              Reason for cancelling (10 to 500 characters)
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3} />
            </label>
            <div className="row">
              <button type="submit" className="btn-danger" disabled={busy || reason.trim().length < 10}>
                Confirm cancellation
              </button>
              <button type="button" className="btn-secondary" onClick={() => setCancelling(false)}>
                Keep requirement
              </button>
            </div>
          </form>
        </div>
      )}

      {detail.state === 'FAILED' && <AnalysisRetryPanel requirementId={detail.id} />}
      <div className="grid grid-main-side">
        <div>
          {reachable.design && (
            // Once the BRD is shared the stages follow one another: show one at a time, opening on the current one,
            // instead of stacking every finished stage above it.
            <Tabs<Stage>
              label="Stages"
              value={stage}
              onChange={pickStage}
              tabs={STAGES.filter((s) => reachable[s]).map((s) => ({
                id: s,
                label: `${done[s] ? '✓ ' : ''}${STAGE_LABELS[s]}${s === currentStage ? ' (current)' : ''}`,
              }))}
            />
          )}
          {stage === 'brd' && (
            <div className="card">
              <BrdPanel requirementId={detail.id} requirementState={detail.state} />
            </div>
          )}
          {stage === 'design' && (
            // GD-26: an approved BRD moves on to the Technical design stage.
            <div className="card">
              <DesignPanel requirementId={detail.id} newProduct={detail.content.kind === 'NEW_PRODUCT'} />
            </div>
          )}
          {stage === 'estimate' && (
            // GD-33: an approved design moves on to Estimation.
            <div className="card">
              <EstimatePanel requirementId={detail.id} />
            </div>
          )}
          {stage === 'plan' && (
            // GD-34: an approved estimate moves on to the Delivery plan.
            <div className="card">
              <PlanPanel requirementId={detail.id} />
            </div>
          )}
          {stage === 'development' && (
            // GD-36: an approved, pushed plan is coded wave by wave.
            <div className="card">
              <DevelopmentPanel requirementId={detail.id} />
            </div>
          )}
          <Card title="Requirement" foldKey={`fold:${detail.id}:requirement`} defaultOpen={EARLY_STATES.has(detail.state)}>
            <ContentView content={detail.content} />
          </Card>
          {detail.content.repositoryUrl && (
            // Earlier requirements linked one repository by URL (C-18); new ones analyse a project environment (GD-23).
            <div className="card">
              <RepositoryLinkPanel requirementId={detail.id} requirementState={detail.state} />
            </div>
          )}
          <div className="card">
            <DocumentsPanel requirementId={detail.id} requirementState={detail.state} />
          </div>
        </div>
        <div>
          <BrdQuotePanel requirementId={detail.id} />
          {detail.content.kind === 'EXISTING_REPOSITORY' && <SourcesPanel requirementId={detail.id} projectId={detail.projectId} />}
          <Card title="Details" foldKey={`fold:${detail.id}:details`}>
            <dl className="details" style={{ gridTemplateColumns: '110px minmax(0, 1fr)' }}>
              <dt>Created</dt>
              <dd>{formatDateTime(detail.createdAt)}</dd>
              <dt>Updated</dt>
              <dd>{relativeTime(detail.updatedAt)}</dd>
              <dt>Your role</dt>
              <dd>{owner ? 'Owner' : <RoleBadges roles={detail.myRoles} />}</dd>
              <dt>Visibility</dt>
              <dd>{detail.restricted ? 'Owner and project Approvers' : 'All project members'}</dd>
            </dl>
            {canRestrict && (
              <button type="button" className="btn-secondary btn-sm" style={{ marginTop: '1rem' }} onClick={() => void toggleRestricted()} disabled={busy}>
                <Icon name={detail.restricted ? 'eye' : 'lock'} size={15} />
                {detail.restricted ? 'Open to the project' : 'Restrict visibility'}
              </button>
            )}
          </Card>
          <Card title="Revisions" flush foldKey={`fold:${detail.id}:revisions`} defaultOpen={false}>
            <ul className="list">
              {detail.revisions.map((r) => (
                <li key={r.revisionNo}>
                  <div className="grow">
                    <div className="title">Revision {r.revisionNo}</div>
                    <span className="small">{formatDateTime(r.createdAt)}</span>
                  </div>
                  <button type="button" className="btn-ghost btn-sm" onClick={() => void viewRevision(r.revisionNo)}>
                    View
                  </button>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="History" flush foldKey={`fold:${detail.id}:history`} defaultOpen={false}>
            <ol className="list">
              {timeline.data?.map((t) => (
                <li key={t.seq}>
                  <Icon name="activity" size={15} />
                  <div className="grow">
                    <div>
                      {t.fromState ? `${stateLabel(t.fromState)} → ` : ''}
                      <strong>{stateLabel(t.toState)}</strong>
                    </div>
                    <span className="small">{formatDateTime(t.occurredAt)}</span>
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </div>
      </div>
      {viewedRevision && (
        <Modal wide title={`Revision ${viewedRevision.revisionNo}: ${viewedRevision.content.title}`} onClose={() => setViewedRevision(null)}>
          <div className="revision" aria-label={`Revision ${viewedRevision.revisionNo}`}>
            <ContentView content={viewedRevision.content} />
          </div>
        </Modal>
      )}
    </>
  );
}
