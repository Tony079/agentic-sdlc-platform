import type { ScopeDiffItem } from '../api/types';

const LABEL: Record<string, string> = { ADDITION: 'Added', MODIFICATION: 'Changed', REMOVAL: 'Removed' };

/** C-16 scope diff: additions, modifications and removals with priority, highest first (server order). */
export function ScopeDiffTable({ diff }: { diff: ScopeDiffItem[] }) {
  if (diff.length === 0) return <p className="small">No scope fields change; the request is described in text only.</p>;
  return (
    <table className="diff">
      <thead>
        <tr>
          <th>Priority</th>
          <th>Area</th>
          <th>Change</th>
          <th>Before</th>
          <th>After</th>
        </tr>
      </thead>
      <tbody>
        {diff.map((d) => (
          <tr key={d.field}>
            <td>
              {d.priority}
              {d.priority !== d.recommendedPriority && (
                <span className="small"> (recommended {d.recommendedPriority}; {d.adjustmentReason})</span>
              )}
            </td>
            <td>{d.area}</td>
            <td>
              {LABEL[d.changeType]} {d.field}
            </td>
            <td className="diff-removed-cell">{d.before ?? '—'}</td>
            <td className="diff-added-cell">{d.after ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
