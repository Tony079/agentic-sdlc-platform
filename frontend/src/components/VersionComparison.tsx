import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { BrdItem, VersionDiff, VersionRef } from '../api/types';

function Statement({ item }: { item: BrdItem }) {
  return (
    <>
      <span className="small">
        [{item.section} · {item.kind} · {item.supportState}]
      </span>{' '}
      {item.statement}
    </>
  );
}

/** TDD 16.8 minimal comparison (O-22 provisional): statement level, by item key, read-only. */
export function VersionComparison({ versionId, versions }: { versionId: string; versions: VersionRef[] }) {
  const others = versions.filter((v) => v.id !== versionId);
  const [against, setAgainst] = useState('');
  const diff = useQuery({
    queryKey: ['brd-diff', versionId, against],
    queryFn: () => api.get<VersionDiff>(`/api/v1/staff/brd-versions/${versionId}/diff?against=${against}`),
    enabled: against !== '',
  });
  if (others.length === 0) return null;
  return (
    <section aria-label="Version comparison">
      <h2>Compare versions</h2>
      <label>
        Compare with
        <select value={against} onChange={(e) => setAgainst(e.target.value)}>
          <option value="">Choose a version…</option>
          {others.map((v) => (
            <option key={v.id} value={v.id}>
              Version {v.versionNo} ({v.status})
            </option>
          ))}
        </select>
      </label>
      {diff.isError && <p role="alert">The comparison is not available.</p>}
      {diff.data && (
        <div>
          <p>
            Version {diff.data.from.versionNo} → version {diff.data.to.versionNo}: {diff.data.added.length} added,{' '}
            {diff.data.removed.length} removed, {diff.data.changed.length} changed, {diff.data.unchanged} unchanged.
            A reworded statement appears as removed and added.
          </p>
          {diff.data.added.length > 0 && (
            <>
              <h3>Added</h3>
              <ul>
                {diff.data.added.map((i) => (
                  <li key={i.itemKey} className="diff-added">
                    <Statement item={i} />
                  </li>
                ))}
              </ul>
            </>
          )}
          {diff.data.removed.length > 0 && (
            <>
              <h3>Removed</h3>
              <ul>
                {diff.data.removed.map((i) => (
                  <li key={i.itemKey} className="diff-removed">
                    <Statement item={i} />
                  </li>
                ))}
              </ul>
            </>
          )}
          {diff.data.changed.length > 0 && (
            <>
              <h3>Changed</h3>
              <ul>
                {diff.data.changed.map((c) => (
                  <li key={c.itemKey}>
                    <Statement item={c.after} /> <span className="small">(changed: {c.fields.join(', ')})</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </section>
  );
}
