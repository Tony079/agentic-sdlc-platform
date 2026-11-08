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
        {c.status} · {c.blocking ? 'blocking' : 'non-blocking'} · from {c.source} · for {c.assignedRole} · asked{' '}
        {new Date(c.createdAt).toLocaleString()}
      </p>
      {c.whyItMatters && (
        <p>
          <strong>Why it matters:</strong> {c.whyItMatters}
        </p>
      )}
      {c.interpretations && (
        <p>
          <strong>Possible interpretations:</strong> {c.interpretations}
        </p>
      )}
      {c.recommendedOption && (
        <p>
          <strong>Recommended:</strong> {c.recommendedOption}
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
