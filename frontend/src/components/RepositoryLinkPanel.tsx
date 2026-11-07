import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { IssuedLinkRequest, RepositoryLinkStatus, RequirementState } from '../api/types';

/** States in which the owner may ask an approver for a repository link (server decides; this hides the control). */
const LINKABLE: RequirementState[] = ['DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING'];

const VALIDATION_LABELS: Record<string, string> = {
  PENDING_VALIDATION: 'Registered, waiting for validation',
  VALIDATED: 'Validated',
  FAILED: 'Validation failed',
};

/**
 * C-18 (owner side): issue a one-time link request for a Client Approver, and follow the repository's status. The
 * token is shown once and never stored by the browser beyond this view.
 */
export function RepositoryLinkPanel({ requirementId, requirementState }: { requirementId: string; requirementState: RequirementState }) {
  const queryClient = useQueryClient();
  const key = ['repository-link', requirementId];
  const status = useQuery({
    queryKey: key,
    queryFn: () => api.get<RepositoryLinkStatus>(`/api/v1/requirements/${requirementId}/repository-link`),
  });
  const [issued, setIssued] = useState<IssuedLinkRequest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function issue() {
    setError(null);
    setBusy(true);
    try {
      setIssued(await api.post<IssuedLinkRequest>(`/api/v1/requirements/${requirementId}/repository-link-requests`, {}));
      await queryClient.invalidateQueries({ queryKey: key });
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'LINK_NOT_ALLOWED_IN_STATE'
        ? 'A repository link cannot be requested for this requirement now.'
        : 'The link request could not be created.');
    } finally {
      setBusy(false);
    }
  }

  async function revoke(requestId: string) {
    setBusy(true);
    try {
      await api.delete(`/api/v1/repository-link-requests/${requestId}`);
      setIssued(null);
      await queryClient.invalidateQueries({ queryKey: key });
    } finally {
      setBusy(false);
    }
  }

  const data = status.data;
  const registered = data?.link?.status === 'REGISTERED';
  return (
    <section aria-label="Repository">
      <h2>Repository</h2>
      {data?.link ? (
        <p>
          Authorised repository: <code>{data.link.repositoryUrl}</code> ·{' '}
          {registered ? 'Registered by the platform team' : 'Waiting for registration by the platform team'}
        </p>
      ) : (
        <p>No repository has been authorised by a Client Approver yet.</p>
      )}
      {data?.validation && (
        <p>
          Validation: {VALIDATION_LABELS[data.validation.status] ?? data.validation.status}
          {data.validation.pinnedSha && (
            <>
              {' '}
              · Commit <code>{data.validation.pinnedSha.substring(0, 12)}</code> ({data.validation.defaultBranch})
            </>
          )}
          {data.validation.snapshotStatus && <> · Snapshot: {data.validation.snapshotStatus.toLowerCase()}</>}
        </p>
      )}
      {issued && (
        <div className="notice" role="status">
          <p>
            Give this one-time code to a Client Approver of your organisation. It is shown only now and expires{' '}
            {new Date(issued.expiresAt).toLocaleString()}. The approver will not see this requirement.
          </p>
          <p>
            <code aria-label="One-time link code">{issued.token}</code>
          </p>
        </div>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!registered && LINKABLE.includes(requirementState) && (
        <div className="actions">
          <button type="button" onClick={() => void issue()} disabled={busy}>
            {data?.pendingRequest ? 'Create a new approver code' : 'Ask an approver to link a repository'}
          </button>
          {data?.pendingRequest && (
            <button type="button" onClick={() => void revoke(data.pendingRequest!.requestId)} disabled={busy}>
              Withdraw the open code
            </button>
          )}
        </div>
      )}
    </section>
  );
}
