import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../api/client';
import type { RequirementContent, RequirementDetail, RequirementRevision, TimelineEntry } from '../api/types';
import { fieldMessage } from '../components/RequirementForm';

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
  });
  const timeline = useQuery({
    queryKey: ['requirement', id, 'timeline'],
    queryFn: () => api.get<TimelineEntry[]>(`/api/v1/requirements/${id}/timeline`),
  });

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
        {editable && !cancelling && (
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

      <h2>History</h2>
      <ol>
        {timeline.data?.map((t) => (
          <li key={t.seq}>
            {t.fromState ? `${t.fromState} → ` : ''}
            {t.toState} · {new Date(t.occurredAt).toLocaleString()}
          </li>
        ))}
      </ol>
    </section>
  );
}
