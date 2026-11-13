import { useEffect, useState, type FormEvent } from 'react';
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
import { AuditHistory } from '../components/AuditHistory';
import { WorkflowProgress } from '../components/WorkflowProgress';

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
  AWAITING_DOCUMENTS: 'The repository is validated. Waiting for documents to finish processing before analysis starts.',
  AWAITING_DOCUMENT_DECISION: 'A document could not be processed. A reviewer must decide whether to exclude it.',
  ANALYSING: 'Analysis is running. This page updates automatically.',
  AWAITING_CLARIFICATION: 'The analysis is paused until an open clarification is answered. See Clarifications.',
  INTERNAL_REVIEW: 'A BRD version is with the internal reviewer. You will see it once it is approved and released.',
  INTERNAL_APPROVED: 'The BRD is approved internally and waits for the release decision.',
  SHARED_WITH_CLIENT: 'The BRD has been released to you. It is shown below.',
  FAILED: 'Processing failed. The history below shows where; platform staff may be able to retry the analysis.',
};

/** States the worker moves on from by itself: the page polls while the requirement is in one of them. */
const IN_MOTION: RequirementState[] = [
  'SUBMITTED', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'ANALYSING', 'BRD_DRAFTED', 'REVISION_REQUESTED',
  'CLIENT_CHANGES_REQUESTED',
];

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
    refetchInterval: (query) => (query.state.data && IN_MOTION.includes(query.state.data.state) ? 4000 : false),
  });
  const timeline = useQuery({
    queryKey: ['requirement', id, 'timeline'],
    queryFn: () => api.get<TimelineEntry[]>(`/api/v1/requirements/${id}/timeline`),
    refetchInterval: requirement.data && IN_MOTION.includes(requirement.data.state) ? 4000 : false,
  });
  // The history must follow the state: when polling sees a new state, fetch the transitions again.
  const currentState = requirement.data?.state;
  useEffect(() => {
    if (currentState) {
      void queryClient.invalidateQueries({ queryKey: ['requirement', id, 'timeline'] });
    }
  }, [currentState, id, queryClient]);

  if (requirement.isPending) {
    return <p>Loading…</p>;
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

  return (
    <section>
      <p>
        <Link to="/requirements">All requirements</Link>
      </p>
      <h1>
        {detail.key}: {detail.content.title}
      </h1>
      <p>
        State: <span className={`state state-${detail.state.toLowerCase()}`}>{detail.state}</span> · Revision{' '}
        {detail.currentRevisionNo}
      </p>
      <WorkflowProgress state={detail.state} />
      {note && (
        <p className="notice" role="status">
          {note}
        </p>
      )}
      {actionError && (
        <p role="alert" className="error">
          {actionError}{' '}
          <button type="button" onClick={() => requirement.refetch()}>
            Reload
          </button>
        </p>
      )}

      <div className="actions">
        {editable && <Link to={`/requirements/${detail.id}/edit`}>Edit</Link>}
        {detail.state === 'DRAFT' && (
          <button type="button" onClick={submit} disabled={busy}>
            Submit
          </button>
        )}
        {cancellable && !cancelling && (
          <button type="button" onClick={() => setCancelling(true)} disabled={busy}>
            Cancel requirement
          </button>
        )}
      </div>

      {cancelling && (
        <form onSubmit={cancel} className="cancel-form">
          <label>
            Reason for cancelling (10 to 500 characters)
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3} />
          </label>
          <button type="submit" disabled={busy || reason.trim().length < 10}>
            Confirm cancellation
          </button>
          <button type="button" onClick={() => setCancelling(false)}>
            Keep requirement
          </button>
        </form>
      )}

      <ContentView content={detail.content} />

      <BrdPanel requirementId={detail.id} requirementState={detail.state} />

      <RepositoryLinkPanel requirementId={detail.id} requirementState={detail.state} />

      <DocumentsPanel requirementId={detail.id} requirementState={detail.state} />

      <h2>Revisions</h2>
      <ul>
        {detail.revisions.map((r) => (
          <li key={r.revisionNo}>
            Revision {r.revisionNo} · {new Date(r.createdAt).toLocaleString()}{' '}
            <button type="button" onClick={() => void viewRevision(r.revisionNo)}>
              View
            </button>
          </li>
        ))}
      </ul>
      {viewedRevision && (
        <div className="revision" aria-label={`Revision ${viewedRevision.revisionNo}`}>
          <h3>
            Revision {viewedRevision.revisionNo}: {viewedRevision.content.title}
          </h3>
          <ContentView content={viewedRevision.content} />
        </div>
      )}

      <h2>Workflow history</h2>
      <ol>
        {timeline.data?.map((t) => (
          <li key={t.seq}>
            {t.fromState ? `${t.fromState} → ` : ''}
            {t.toState} · {new Date(t.occurredAt).toLocaleString()}
          </li>
        ))}
      </ol>

      <AuditHistory requirementId={detail.id} />
    </section>
  );
}
