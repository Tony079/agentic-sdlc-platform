import { useState } from 'react';
import type { BrdEvidence, BrdItem } from '../api/types';
import { FoldToggle } from '../ui/Fold';

// GD-24 template order; FACTS is kept for BRDs produced before it.
const SECTION_TITLES: Record<string, string> = {
  CONTEXT: 'Context',
  CURRENT_SYSTEM: 'How the system works today',
  FACTS: 'Facts',
  IMPACTED_COMPONENTS: 'Impact',
  SCENARIOS: 'Scenarios',
  RISKS: 'Risks',
  ASSUMPTIONS: 'Assumptions',
  NON_FUNCTIONAL: 'Non-functional points',
  ACCEPTANCE_CRITERIA: 'Acceptance criteria',
  MISSING_REQUIREMENTS: 'Missing requirements',
  RECOMMENDATIONS: 'Recommendations',
  QUESTIONS: 'Open questions',
  UNSUPPORTED_REQUIREMENTS: 'Requirements not traced to evidence',
};

const SUPPORT_LABELS: Record<string, string> = {
  VERIFIED: 'Evidence verified',
  FLAGGED: 'Flagged: the cited text may not support this',
  UNSUPPORTED: 'No verified evidence',
};

function evidenceText(e: BrdEvidence): string {
  if (e.type === 'REQUIREMENT') {
    return `Requirement: ${e.chunkLabel ?? e.path}`;
  }
  if (e.type === 'REPOSITORY') {
    return `${e.path} lines ${e.startLine}–${e.endLine} @ ${e.commitSha?.substring(0, 12)}`;
  }
  return `${e.fileName ?? 'document'}${e.chunkLabel ? `, ${e.chunkLabel}` : ''} (part ${e.chunk})`;
}

/** Citations of the same source (for example two passages of the requirement's description) are shown once. */
function groupEvidence(evidence: BrdEvidence[]): { label: string; count: number; quotes: string[] }[] {
  const groups = new Map<string, { label: string; count: number; quotes: string[] }>();
  for (const e of evidence) {
    const label = evidenceText(e);
    const group = groups.get(label) ?? { label, count: 0, quotes: [] };
    group.count += 1;
    if (e.quote) group.quotes.push(e.quote);
    groups.set(label, group);
  }
  return [...groups.values()];
}

/**
 * BRD statements grouped by section with their classification label (TDD 16.4) and evidence references. Quotes are
 * shown only when the server sent them (reviewers); client views never contain source excerpts (AC-EXT-29).
 */
export function BrdItems({ items, renderActions }: { items: BrdItem[]; renderActions?: (item: BrdItem) => React.ReactNode }) {
  const sections = Object.keys(SECTION_TITLES).filter((s) => items.some((i) => i.section === s));
  const [folded, setFolded] = useState<Set<string>>(new Set());
  if (items.length === 0) {
    return <p>This BRD version has no statements.</p>;
  }
  const allFolded = sections.every((s) => folded.has(s));
  const toggle = (section: string) => setFolded((f) => {
    const next = new Set(f);
    if (next.has(section)) next.delete(section);
    else next.add(section);
    return next;
  });
  return (
    <>
      <div className="fold-all">
        <button type="button" className="btn-ghost btn-sm" onClick={() => setFolded(allFolded ? new Set() : new Set(sections))}>
          {allFolded ? 'Expand all sections' : 'Collapse all sections'}
        </button>
      </div>
      {sections.map((section) => {
        const count = items.filter((i) => i.section === section).length;
        return (
        <section key={section} aria-label={SECTION_TITLES[section]}>
          <FoldToggle level={3} open={!folded.has(section)} onToggle={() => toggle(section)}>
            {SECTION_TITLES[section]} <span className="small">({count})</span>
          </FoldToggle>
          {!folded.has(section) && (
          <ul className="brd-items">
            {items
              .filter((i) => i.section === section)
              .map((item) => (
                <li key={item.itemKey} className={`support-${item.supportState.toLowerCase()}`}>
                  <span className="label">{item.kind}</span> {item.statement}
                  <div className="small">
                    {SUPPORT_LABELS[item.supportState]}
                    {item.kind !== item.proposedKind && ` · proposed as ${item.proposedKind}, citation did not verify`}
                    {item.risk && ` · Risk: ${item.risk}`}
                    {item.component && ` · Component: ${item.component}`}
                  </div>
                  {item.evidence.length > 0 && (
                    <ul className="evidence">
                      {groupEvidence(item.evidence).map((g) => (
                        <li key={g.label}>
                          <code>{g.label}</code>
                          {g.quotes.length === 0 && g.count > 1 && <span className="small"> ({g.count} passages)</span>}
                          {g.quotes.map((q, i) => <blockquote key={i}>{q}</blockquote>)}
                        </li>
                      ))}
                    </ul>
                  )}
                  {renderActions?.(item)}
                </li>
              ))}
          </ul>
          )}
        </section>
        );
      })}
    </>
  );
}
