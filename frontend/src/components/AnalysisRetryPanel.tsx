import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import { Callout } from '../ui/components';

interface AuthorRetry {
  failed: boolean;
  canRetry: boolean;
  used: number;
  limit: number;
  failureCode: string | null;
  message: string | null;
  pending: boolean;
}

const REASONS: Record<string, string> = {
  MODEL_INVALID_OUTPUT: 'The AI model returned an answer Garuda could not use.',
  MODEL_EMPTY_OUTPUT: 'The AI model returned an empty answer.',
  MODEL_PROVIDER_ERROR: 'The AI model service returned an error.',
  MODEL_UNAVAILABLE: 'The AI model service was unavailable.',
  COST_CAP: 'The analysis reached its spending cap.',
};

/** The author retries a failed analysis (at most three times, not charged again). */
export function AnalysisRetryPanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const wasPending = useRef(false);
  const retry = useQuery({
    queryKey: ['analysis-retry', requirementId],
    queryFn: async () => {
      const result = await api.get<AuthorRetry>(`/api/v1/requirements/${requirementId}/analysis-retry`);
      // The worker picks a retry up within seconds; then the requirement moves on (Analysing).
      if (wasPending.current && !result.pending) {
        void queryClient.invalidateQueries({ queryKey: ['requirement', requirementId] });
      }
      wasPending.current = result.pending;
      return result;
    },
    refetchInterval: (q) => (q.state.data?.pending ? 3000 : false),
  });
  const r = retry.data;
  if (!r?.failed) return null;

  async function run() {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/v1/requirements/${requirementId}/analysis-retry`);
      await queryClient.invalidateQueries();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The retry could not be started.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Callout tone="danger" title="The analysis failed">
      <p style={{ margin: 0 }}>
        {(r.failureCode && REASONS[r.failureCode]) ?? 'The analysis stopped before a BRD was produced.'}
        {r.failureCode && <span className="small"> (code {r.failureCode})</span>}
      </p>
      {r.message && <p className="small" style={{ margin: '0.4rem 0 0' }}>{r.pending ? 'Restarting the analysis…' : r.message}</p>}
      {r.canRetry && (
        <button type="button" className="btn-primary btn-sm" style={{ marginTop: '0.6rem' }} disabled={busy} onClick={() => void run()}>
          Retry analysis
        </button>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </Callout>
  );
}
