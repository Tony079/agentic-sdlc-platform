import type { BrdEvidence, BrdItem } from '../api/types';

const SECTION_TITLES: Record<string, string> = {
  FACTS: 'Facts',
  ASSUMPTIONS: 'Assumptions',
  RISKS: 'Risks',
  IMPACTED_COMPONENTS: 'Impacted components',
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
  if (e.type === 'REPOSITORY') {
    return `${e.path} lines ${e.startLine}–${e.endLine} @ ${e.commitSha?.substring(0, 12)}`;
  }
  return `${e.fileName ?? 'document'}${e.chunkLabel ? `, ${e.chunkLabel}` : ''} (part ${e.chunk})`;
}

/**
 * BRD statements grouped by section with their classification label (TDD 16.4) and evidence references. Quotes are
 * shown only when the server sent them (reviewers); client views never contain source excerpts (AC-EXT-29).
 */
export function BrdItems({ items, renderActions }: { items: BrdItem[]; renderActions?: (item: BrdItem) => React.ReactNode }) {
  const sections = Object.keys(SECTION_TITLES).filter((s) => items.some((i) => i.section === s));
  if (items.length === 0) {
    return <p>This BRD version has no statements.</p>;
  }
  return (
    <>
      {sections.map((section) => (
        <section key={section} aria-label={SECTION_TITLES[section]}>
          <h3>{SECTION_TITLES[section]}</h3>
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
                      {item.evidence.map((e, index) => (
                        <li key={index}>
                          <code>{evidenceText(e)}</code>
                          {e.quote && <blockquote>{e.quote}</blockquote>}
                        </li>
                      ))}
                    </ul>
                  )}
                  {renderActions?.(item)}
                </li>
              ))}
          </ul>
        </section>
      ))}
    </>
  );
}
