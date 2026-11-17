import { useState } from 'react';
import { api, ApiError } from '../api/client';
import type { Clarification } from '../api/types';

const ERRORS: Record<string, string> = {
  CLARIFICATION_STATE: 'This question is no longer open.',
  NOT_ASSIGNED_ROLE: 'This question is assigned to someone else.',
  FORBIDDEN: 'You are not allowed to answer this question.',
};

/**
 * One clarification (TDD 17, FR-CLR): the question, why it matters, the interpretations and the recommended option,
 * the conversation, and answer / reject actions. {@code basePath} is the client or the staff endpoint.
 */
const SOURCE: Record<string, string> = { AGENT: 'BA agent', REVIEWER: 'Garuda reviewer', CLIENT: 'client' };
const STATUS: Record<string, string> = {
  OPEN: 'Open', NEEDS_INFO: 'More information needed', ANSWERED: 'Answered', RESOLVED: 'Resolved',
  REJECTED: 'Not applicable', WITHDRAWN: 'Withdrawn',
};

/** The agent's interpretations arrive as a JSON array of strings; older or plain text is shown as it is. */
function parseInterpretations(value: string | null): string[] {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.filter((x): x is string => typeof x === 'string');
  } catch {
    // not JSON
  }
  return [value];
}

export function ClarificationCard({
  clarification: c,
  basePath,
  onChanged,
  staffActions = false,
}: {
  clarification: Clarification;
  basePath: string;
  onChanged: () => void;
  staffActions?: boolean;
}) {
  const [text, setText] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const open = c.status === 'OPEN' || c.status === 'NEEDS_INFO';

  async function act(path: string, body: unknown, success: string) {
    setBusy(true);
    setMessage(null);
    try {
      await api.post(`${basePath}/${c.id}/${path}`, body);
      setText('');
      setMessage(success);
      onChanged();
    } catch (e) {
      setMessage(e instanceof ApiError ? (ERRORS[e.code] ?? e.message) : 'The action failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="card" aria-label={`Clarification: ${c.question}`}>
      <h3>{c.question}</h3>
      <p className="small">
        {STATUS[c.status] ?? c.status} · {c.blocking ? 'blocking: the analysis waits for your answer' : 'non-blocking'} ·
        asked by the {SOURCE[c.source] ?? c.source} · {new Date(c.createdAt).toLocaleString()}
      </p>
      {c.whyItMatters && (
        <p>
          <strong>Why it matters:</strong> {c.whyItMatters}
        </p>
      )}
      {parseInterpretations(c.interpretations).length > 0 && (
        <div>
          <strong>Possible answers</strong>
          {open && <span className="small"> (choose one to use it as your answer, then edit it if needed)</span>}
          <ul className="stack" style={{ listStyle: 'none', paddingLeft: 0, marginTop: '0.4rem' }}>
            {parseInterpretations(c.interpretations).map((option) => (
              <li key={option} className="row" style={{ alignItems: 'flex-start' }}>
                {open && (
                  <button type="button" className="btn-secondary btn-sm" onClick={() => setText(option)}>
                    Use
                  </button>
                )}
                <span>
                  {option}
                  {option === c.recommendedOption && <span className="small"> · recommended by the agent</span>}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {c.recommendedOption && !parseInterpretations(c.interpretations).includes(c.recommendedOption) && (
        <p>
          <strong>Recommended by the agent:</strong> {c.recommendedOption}
        </p>
      )}
      {c.messages.length > 0 && (
        <ul>
          {c.messages.map((m, i) => (
            <li key={i}>
              <span className="small">
                {m.kind} · {m.authorType} · {new Date(m.createdAt).toLocaleString()}
              </span>{' '}
              {m.text}
            </li>
          ))}
        </ul>
      )}
      {open && (
        <div className="actions">
          <label>
            Your answer or reason
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} maxLength={8000} />
          </label>
          <button type="button" disabled={busy || !text.trim()} onClick={() => void act('answer', { text }, 'Answer sent.')}>
            Answer
          </button>
          <button
            type="button"
            disabled={busy || !text.trim()}
            onClick={() => void act('reject', { reason: text }, 'Marked as not applicable.')}
          >
            Not applicable
          </button>
          {staffActions && (
            <button
              type="button"
              disabled={busy || !text.trim()}
              onClick={() => void act('request-info', { reason: text }, 'More information requested.')}
            >
              Request more information
            </button>
          )}
        </div>
      )}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
    </article>
  );
}
