import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AuditEntry } from '../api/types';

const ACTOR_LABELS: Record<AuditEntry['actor'], string> = {
  YOU: 'You',
  USER: 'Another user of your organisation',
  STAFF: 'Platform staff',
  SYSTEM: 'System',
};

function label(code: string): string {
  const text = code.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Audit history of one requirement and its documents, clarifications and BRD versions. Loaded on request. Entries
 * hold codes only: the audit trail never stores business content.
 */
export function AuditHistory({ requirementId }: { requirementId: string }) {
  const [open, setOpen] = useState(false);
  const audit = useQuery({
    queryKey: ['requirement', requirementId, 'audit'],
    queryFn: () => api.get<AuditEntry[]>(`/api/v1/requirements/${requirementId}/audit`),
    enabled: open,
  });

  return (
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit history</h2>
      {!open && (
        <button type="button" onClick={() => setOpen(true)}>
          Show audit history
        </button>
      )}
      {open && (
        <>
          <button type="button" onClick={() => void audit.refetch()} disabled={audit.isFetching}>
            Refresh
          </button>{' '}
          <button type="button" onClick={() => setOpen(false)}>
            Hide
          </button>
          {audit.isPending && <p>Loading audit history…</p>}
          {audit.isError && (
            <p role="alert" className="error">
              The audit history could not be loaded.
            </p>
          )}
          {audit.data && audit.data.length === 0 && <p>No audit entries yet.</p>}
          {audit.data && audit.data.length > 0 && (
            <table>
              <caption className="small">
                {audit.data.length} entries, oldest first. Entries record what happened, never the content.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Action</th>
                  <th scope="col">On</th>
                  <th scope="col">By</th>
                  <th scope="col">Result</th>
                </tr>
              </thead>
              <tbody>
                {audit.data.map((entry, index) => (
                  <tr key={`${entry.occurredAt}-${entry.action}-${index}`}>
                    <td>{new Date(entry.occurredAt).toLocaleString()}</td>
                    <td>{label(entry.action)}</td>
                    <td>{label(entry.resourceType)}</td>
                    <td>{ACTOR_LABELS[entry.actor] ?? entry.actor}</td>
                    <td>{label(entry.result)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
