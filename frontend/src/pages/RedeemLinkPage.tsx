import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import type { RedeemedLink } from '../api/types';
import { PageHeader } from '../ui/components';

/**
 * C-18 (Client Approver): attach a repository with a one-time code from the requirement owner. Records the client's
 * authorisation of the repository (Q-A). The approver is told nothing about the requirement.
 */
export function RedeemLinkPage() {
  const [token, setToken] = useState('');
  const [repositoryUrl, setRepositoryUrl] = useState('');
  const [result, setResult] = useState<RedeemedLink | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      setResult(await api.post<RedeemedLink>('/api/v1/repository-link-requests/redeem', {
        token: token.trim(),
        repositoryUrl: repositoryUrl.trim(),
      }));
      setToken('');
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        setError('Enter the repository as https://github.com/OWNER/REPOSITORY.');
      } else {
        setError('This code is not valid. It may be wrong, expired or already used. Ask the requirement owner for a new one.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <PageHeader eyebrow="Approvals" title="Link a repository" description="Authorise a repository for a requirement using the code its owner shared with you." />
      <p>
        By submitting, you authorise the platform to analyse this GitHub repository for the requirement the code was
        issued for. The platform team registers it next; the GitHub App must also be installed on the repository.
      </p>
      <form onSubmit={submit} noValidate>
        <label>
          One-time code
          <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" required />
        </label>
        <label>
          Repository URL
          <input
            value={repositoryUrl}
            onChange={(e) => setRepositoryUrl(e.target.value)}
            placeholder="https://github.com/owner/repository"
            required
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={busy || !token.trim() || !repositoryUrl.trim()}>
          Authorise repository
        </button>
      </form>
      {result && (
        <p className="notice" role="status">
          Repository authorised. Reference {result.linkId.substring(0, 8)}. The platform team will register it.
        </p>
      )}
    </section>
  );
}
