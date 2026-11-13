import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
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
import { RepositoryLinkPanel } from '../components/RepositoryLinkPanel';
import { Icon } from '../ui/Icon';
import {
  BackLink,
  Badge,
  Card,
  Loading,
  Modal,
  PageHeader,
  RoleBadge,
  StatePill,
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
      <dd>{content.kind === 'NEW_PRODUCT' ? 'New product' : 'Existing repository'}</dd>
      {CONTENT_LABELS.filter(([field]) => content.kind === 'EXISTING_REPOSITORY' || field !== 'repositoryUrl').map(
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
    'Waiting for repository validation. Repository connection and analysis are not available yet, so the requirement stays here for now.',
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

export function RequirementDetailPage() {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [viewedRevision, setViewedRevision] = useState<RequirementRevision | null>(null);
  const [busy, setBusy] = useState(false);

  const requirement = useQuery({
    queryKey: ['requirement', id],
    queryFn: () => api.get<RequirementDetail>(`/api/v1/requirements/${id}`),
    // Routing happens asynchronously in the worker: poll briefly while the requirement is SUBMITTED.
    refetchInterval: (query) => (query.state.data?.state === 'SUBMITTED' ? 3000 : false),
  });
  const timeline = useQuery({
    queryKey: ['requirement', id, 'timeline'],
    queryFn: () => api.get<TimelineEntry[]>(`/api/v1/requirements/${id}/timeline`),
    refetchInterval: requirement.data?.state === 'SUBMITTED' ? 3000 : false,
  });

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
  const canRestrict = owner || detail.myRole === 'APPROVER';
  return (
    <>
      <BackLink to="/requirements">All requirements</BackLink>
      <PageHeader
        eyebrow={<span className="mono">{detail.key}</span>}
        title={detail.content.title}
        description={
          <span className="row">
            <span className="sr-only">State:</span>
            <StatePill state={detail.state} />
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

      <div className="grid grid-main-side">
        <div>
          <Card title="Requirement">
            <ContentView content={detail.content} />
          </Card>
          <div className="card">
            <BrdPanel requirementId={detail.id} requirementState={detail.state} />
          </div>
          <div className="card">
            <RepositoryLinkPanel requirementId={detail.id} requirementState={detail.state} />
          </div>
          <div className="card">
            <DocumentsPanel requirementId={detail.id} requirementState={detail.state} />
          </div>
        </div>
        <div>
          <Card title="Details">
            <dl className="details" style={{ gridTemplateColumns: '110px minmax(0, 1fr)' }}>
              <dt>Created</dt>
              <dd>{formatDateTime(detail.createdAt)}</dd>
              <dt>Updated</dt>
              <dd>{relativeTime(detail.updatedAt)}</dd>
              <dt>Your role</dt>
              <dd>{owner ? 'Owner' : <RoleBadge role={detail.myRole} />}</dd>
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
          <Card title="Revisions" flush>
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
          <Card title="History" flush>
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
