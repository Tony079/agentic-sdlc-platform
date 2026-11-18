import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon, type IconName } from './Icon';

/* ---------- page structure ---------- */

export function PageHeader({
  title,
  eyebrow,
  description,
  actions,
}: {
  title: ReactNode;
  eyebrow?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="page-header">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p className="description">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Card({
  title,
  actions,
  children,
  footer,
  flush,
  className,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  flush?: boolean;
  className?: string;
}) {
  const classes = ['card', title || flush ? 'card-flush' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <section className={classes}>
      {title && (
        <div className="card-header">
          <h2>{title}</h2>
          {actions && <div className="row">{actions}</div>}
        </div>
      )}
      {title && !flush ? <div className="card-body">{children}</div> : children}
      {footer && <div className="card-footer">{footer}</div>}
    </section>
  );
}

export function Stat({
  label,
  value,
  meta,
  icon,
}: {
  label: string;
  value: ReactNode;
  meta?: ReactNode;
  icon?: IconName;
}) {
  return (
    <div className="stat">
      <div className="stat-label">
        {icon && (
          <span className="stat-icon">
            <Icon name={icon} size={16} />
          </span>
        )}
        {label}
      </div>
      <div className="stat-value">{value}</div>
      {meta && <div className="stat-meta">{meta}</div>}
    </div>
  );
}

export function EmptyState({
  icon = 'inbox',
  title,
  children,
  action,
}: {
  icon?: IconName;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Icon name={icon} size={22} />
      </span>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" />
      {label}
    </div>
  );
}

export function Callout({
  tone = 'info',
  title,
  children,
}: {
  tone?: 'info' | 'warning' | 'success' | 'danger';
  title?: string;
  children: ReactNode;
}) {
  const icon: IconName = tone === 'success' ? 'checkCircle' : tone === 'info' ? 'info' : 'alert';
  return (
    <div className={`callout callout-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon name={icon} />
      <div>
        {title && <strong>{title}</strong>}
        {title ? <div>{children}</div> : children}
      </div>
    </div>
  );
}

export function ErrorText({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="error">
      {children}
    </p>
  );
}

/* ---------- badges ---------- */

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'violet' | 'neutral' | 'primary';

export function Badge({ tone = 'neutral', children, dot = true }: { tone?: Tone; children: ReactNode; dot?: boolean }) {
  return <span className={`badge tone-${tone}${dot ? '' : ' no-dot'}`}>{children}</span>;
}

const STATE_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  MANUAL_INTAKE: 'Manual intake',
  REPO_VALIDATING: 'Preparing code',
  AWAITING_DOCUMENTS: 'Awaiting documents',
  AWAITING_DOCUMENT_DECISION: 'Document decision',
  ANALYSING: 'Analysing',
  AWAITING_CLARIFICATION: 'Awaiting clarification',
  BRD_DRAFTED: 'BRD drafted',
  INTERNAL_REVIEW: 'BRD in review',
  REVISION_REQUESTED: 'Changes requested',
  INTERNAL_APPROVED: 'Approved, publishing',
  SHARED_WITH_CLIENT: 'BRD approved',
  CLIENT_CHANGES_REQUESTED: 'Changes requested',
  REJECTED: 'Closed',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

export function stateLabel(state: string): string {
  return STATE_LABELS[state] ?? state.charAt(0) + state.slice(1).toLowerCase().replaceAll('_', ' ');
}

/** GD-27: once the BRD is approved the requirement shows its technical design status instead. */
const DESIGN_STATUS: Record<string, { label: string; cls: string }> = {
  NONE: { label: 'Waiting for technical design', cls: 'internal_review' },
  GENERATING: { label: 'Design being written', cls: 'analysing' },
  IN_REVIEW: { label: 'Design in review', cls: 'internal_review' },
  APPROVED: { label: 'Design approved', cls: 'shared_with_client' },
  FAILED: { label: 'Design failed', cls: 'failed' },
  SUPERSEDED: { label: 'Design in review', cls: 'internal_review' },
};

/**
 * Workflow state of a requirement, coloured by meaning (waiting, in progress, done, failed). After BRD approval it
 * shows the technical design status when {@code designStatus} is given (null = not started yet).
 */
export function StatePill({ state, designStatus }: { state: string; designStatus?: string | null }) {
  if (state === 'SHARED_WITH_CLIENT' && designStatus !== undefined) {
    const d = DESIGN_STATUS[designStatus ?? 'NONE'] ?? { label: 'Waiting for technical design', cls: 'internal_review' };
    return (
      <span className={`state state-${d.cls}`} title={`BRD approved · ${d.label}`}>
        {d.label}
      </span>
    );
  }
  return (
    <span className={`state state-${state.toLowerCase()}`} title={state}>
      {stateLabel(state)}
    </span>
  );
}

export const ROLE_LABELS: Record<string, string> = {
  ORG_ADMIN: 'Organisation Admin',
  MEMBER: 'Member',
  REQUIREMENT_AUTHOR: 'Requirement Author',
  BRD_APPROVER: 'BRD Approver',
  TECHNICAL_APPROVER: 'Technical Approver',
  VIEWER: 'Viewer',
};

/** GD-27: whether the roles allow creating requirements. */
export function canRequest(roles: readonly string[] | null | undefined): boolean {
  return !!roles?.some((r) => r === 'REQUIREMENT_AUTHOR' || r === 'BRD_APPROVER');
}

export function RoleBadge({ role }: { role: string | null | undefined }) {
  if (!role) {
    return <Badge tone="neutral">No access</Badge>;
  }
  const tone: Tone = role === 'ORG_ADMIN' || role === 'BRD_APPROVER' ? 'primary'
    : role === 'TECHNICAL_APPROVER' ? 'violet' : role === 'REQUIREMENT_AUTHOR' ? 'info' : 'neutral';
  return <Badge tone={tone}>{ROLE_LABELS[role] ?? role}</Badge>;
}

/** GD-27: a member's roles in one project, one badge each ("No access" when none). */
export function RoleBadges({ roles }: { roles: readonly string[] | null | undefined }) {
  if (!roles || roles.length === 0) {
    return <RoleBadge role={null} />;
  }
  return (
    <span className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
      {roles.map((r) => <RoleBadge key={r} role={r} />)}
    </span>
  );
}

/* ---------- avatar ---------- */

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + last).toUpperCase() || '?';
}

export function Avatar({ name, size, square }: { name: string; size?: 'sm'; square?: boolean }) {
  return (
    <span className={`avatar${size ? ` avatar-${size}` : ''}${square ? ' avatar-square' : ''}`} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

/* ---------- tabs ---------- */

export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
  label,
}: {
  tabs: { id: T; label: string; count?: number }[];
  value: T;
  onChange: (id: T) => void;
  label: string;
}) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button
          key={t.id}
          type="button"
          role="tab"
          className="tab"
          aria-selected={t.id === value}
          onClick={() => onChange(t.id)}
        >
          {t.label}
          {t.count !== undefined && <span className="badge no-dot tone-neutral">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

/* ---------- modal ---------- */

export function Modal({
  title,
  description,
  children,
  footer,
  onClose,
  wide,
}: {
  title: string;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const id = useId();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('input, select, textarea, button:not(.modal-close)');
    first?.focus();
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previous?.focus();
    };
  }, [onClose]);
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className={`modal${wide ? ' modal-wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={id} ref={ref}>
        <div className="modal-header">
          <div>
            <h2 id={id}>{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <button type="button" className="btn-ghost btn-icon modal-close" onClick={onClose} aria-label="Close">
            <Icon name="x" />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}

/* ---------- menu ---------- */

export function ActionMenu({ label, children }: { label: string; children: (close: () => void) => ReactNode }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    function onDown(event: MouseEvent) {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  return (
    <div className="user-menu" ref={ref}>
      <button
        type="button"
        className="btn-ghost btn-icon btn-sm"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="more" />
      </button>
      {open && (
        <div className="menu" role="menu">
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  );
}

/* ---------- copy field ---------- */

export function CopyField({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  }
  return (
    <div className="copy-field">
      <input readOnly value={value} aria-label={label} onFocus={(e) => e.currentTarget.select()} />
      <button type="button" className="btn-secondary" onClick={() => void copy()}>
        <Icon name={copied ? 'check' : 'copy'} size={16} />
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}

/* ---------- misc ---------- */

export function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link to={to} className="row small" style={{ marginBottom: '0.75rem', display: 'inline-flex' }}>
      <Icon name="arrowLeft" size={15} />
      {children}
    </Link>
  );
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function relativeTime(value: string | null | undefined): string {
  if (!value) return '—';
  const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 60) return 'just now';
  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, 'minute'],
    [3600, 'hour'],
    [86400, 'day'],
    [604800, 'week'],
    [2629800, 'month'],
    [31557600, 'year'],
  ];
  let unit: Intl.RelativeTimeFormatUnit = 'minute';
  let size = 60;
  for (const [s, u] of units) {
    if (abs >= s) {
      unit = u;
      size = s;
    }
  }
  return new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' }).format(-Math.round(seconds / size), unit);
}
