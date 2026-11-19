import { useState, type ReactNode } from 'react';
import { Icon } from './Icon';

function read(key: string | undefined): boolean | null {
  if (!key) return null;
  try {
    const v = window.localStorage.getItem(key);
    return v === '1' ? true : v === '0' ? false : null;
  } catch {
    return null;
  }
}

function write(key: string | undefined, open: boolean) {
  if (!key) return;
  try {
    window.localStorage.setItem(key, open ? '1' : '0');
  } catch {
    // storage unavailable (private mode): the choice lasts for this page only
  }
}

/** Open/closed state of a foldable section; remembered in this browser under {@code key} when given. */
export function useFold(key: string | undefined, defaultOpen: boolean): [boolean, () => void, (open: boolean) => void] {
  const [open, setOpen] = useState<boolean>(() => read(key) ?? defaultOpen);
  const set = (next: boolean) => {
    setOpen(next);
    write(key, next);
  };
  return [open, () => set(!open), set];
}

/** The clickable title of a foldable section: chevron plus heading, announced as expanded or collapsed. */
export function FoldToggle({ open, onToggle, level = 2, children, controls }: {
  open: boolean;
  onToggle: () => void;
  level?: 2 | 3;
  children: ReactNode;
  controls?: string;
}) {
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <button type="button" className={`fold-toggle fold-toggle-${level}`} aria-expanded={open} aria-controls={controls}
      onClick={onToggle}>
      <Icon name={open ? 'chevronDown' : 'chevronRight'} size={level === 2 ? 18 : 16} />
      <Heading>{children}</Heading>
    </button>
  );
}

/**
 * A section that folds like a group box: the title bar toggles the body; {@code actions} (badges, downloads) stay
 * visible when folded; {@code summary} is shown in place of the body while folded.
 */
export function FoldSection({ title, foldKey, defaultOpen = true, actions, summary, children, label }: {
  title: ReactNode;
  foldKey?: string;
  defaultOpen?: boolean;
  actions?: ReactNode;
  summary?: ReactNode;
  children: ReactNode;
  label?: string;
}) {
  const [open, toggle] = useFold(foldKey, defaultOpen);
  const id = foldKey ? `fold-${foldKey.replace(/[^a-zA-Z0-9-]/g, '-')}` : undefined;
  return (
    <section aria-label={label} className={open ? 'fold open' : 'fold'}>
      <div className="spread fold-header">
        <FoldToggle open={open} onToggle={toggle} controls={id}>{title}</FoldToggle>
        {actions && <div className="row">{actions}</div>}
      </div>
      {open ? <div id={id}>{children}</div> : summary && <div className="small fold-summary">{summary}</div>}
    </section>
  );
}
