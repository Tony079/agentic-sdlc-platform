import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Icon } from './Icon';

interface ToastItem {
  id: number;
  tone: 'success' | 'error';
  message: string;
}

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

/** Short confirmations of completed actions; errors that need attention stay inline next to the form instead. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const dismiss = useCallback((id: number) => setItems((all) => all.filter((t) => t.id !== id)), []);
  const push = useCallback(
    (tone: ToastItem['tone'], message: string) => {
      const id = Date.now() + Math.random();
      setItems((all) => [...all.slice(-3), { id, tone, message }]);
      window.setTimeout(() => dismiss(id), 5000);
    },
    [dismiss],
  );
  const api = useMemo<ToastApi>(
    () => ({ success: (m) => push('success', m), error: (m) => push('error', m) }),
    [push],
  );
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toasts" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone}`} role="status">
            <Icon name={t.tone === 'success' ? 'checkCircle' : 'alert'} />
            <span>{t.message}</span>
            <button type="button" className="btn-ghost btn-icon btn-sm toast-close" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
              <Icon name="x" size={15} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

const SILENT: ToastApi = { success: () => undefined, error: () => undefined };

export function useToast(): ToastApi {
  return useContext(ToastContext) ?? SILENT;
}
