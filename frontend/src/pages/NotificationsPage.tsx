import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { NotificationItem } from '../api/types';
import { Badge, Card, EmptyState, Loading, PageHeader, relativeTime, formatDateTime } from '../ui/components';

const KINDS: Record<string, string> = {
  BRD_RELEASED: 'A business requirements document was shared with you.',
  CLARIFICATION_ASSIGNED: 'The BA agent has questions for you. Analysis continues after your answers.',
  BRD_READY_FOR_REVIEW: 'A BRD is ready for your review.',
  BRD_AWAITING_APPROVAL: 'A BRD was confirmed by its author and waits for your approval.',
  BRD_APPROVED: 'A BRD was approved and published to the project.',
  BRD_PUBLISH_BLOCKED: 'An approved BRD could not be published yet: an automatic check did not pass.',
  BRD_NOT_FEASIBLE: 'The BA agent found a requirement not feasible; it was closed and not charged.',
  TDD_HANDOVER: 'An approved BRD was handed to you for technical design.',
  TDD_NO_TECHNICAL_APPROVER: 'A BRD was approved, but its project has no Technical Approver. Assign one to start the design.',
  TDD_READY_FOR_REVIEW: 'A technical design is ready for review.',
  TDD_APPROVED: 'A technical design was approved.',
  TDD_FAILED: 'A technical design could not be written. You can try again from the requirement.',
  ESTIMATE_HANDOVER: 'An approved technical design was handed to you for estimation.',
  ESTIMATE_NO_ESTIMATOR: 'A design was approved, but its project has no Estimator. Assign one to start the estimate.',
  ESTIMATE_READY_FOR_REVIEW: 'An estimate is ready for review.',
  ESTIMATE_APPROVED: 'An estimate was approved.',
  ESTIMATE_FAILED: 'An estimate could not be written. You can try again from the requirement.',
  PLAN_HANDOVER: 'An approved estimate was handed to you for the delivery plan.',
  PLAN_NO_ESTIMATOR: 'An estimate was approved, but its project has no Estimator to plan it. Assign one.',
  PLAN_READY_FOR_REVIEW: 'A delivery plan is ready for review.',
  PLAN_APPROVED: 'A delivery plan was approved.',
  PLAN_FAILED: 'A delivery plan could not be written. You can try again from the requirement.',
};

interface NotificationSettings {
  email: boolean;
  emailAvailable: boolean;
}

/** GD-25: email delivery of notifications, on or off for the signed-in member. */
function EmailSetting() {
  const queryClient = useQueryClient();
  const settings = useQuery({
    queryKey: ['notification-settings'],
    queryFn: () => api.get<NotificationSettings>('/api/v1/notifications/settings'),
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  if (!settings.data) return null;

  async function toggle(email: boolean) {
    setBusy(true);
    setError(false);
    try {
      const next = await api.put<NotificationSettings>('/api/v1/notifications/settings', { email });
      queryClient.setQueryData(['notification-settings'], next);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <label className="row" style={{ gap: '0.5rem', cursor: 'pointer' }}>
      <input
        type="checkbox"
        checked={settings.data.email}
        disabled={busy}
        onChange={(e) => void toggle(e.target.checked)}
      />
      <span>
        Also send these to me by email
        {!settings.data.emailAvailable && <span className="small"> (email is not set up for Garuda yet)</span>}
        {error && <span className="error small"> Could not save the setting.</span>}
      </span>
    </label>
  );
}

export function NotificationsPage() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['notifications'], queryFn: () => api.get<NotificationItem[]>('/api/v1/notifications') });
  const unread = (list.data ?? []).filter((n) => !n.readAt);

  async function markRead(ids: string[]) {
    await Promise.all(ids.map((id) => api.post(`/api/v1/notifications/${id}/read`)));
    await queryClient.invalidateQueries({ queryKey: ['notifications'] });
  }

  return (
    <section>
      <PageHeader
        title="Notifications"
        description="Updates on the requirements you work on."
        actions={<EmailSetting />}
      />
      {list.isPending && <Loading />}
      {list.data?.length === 0 && (
        <EmptyState icon="inbox" title="You are all caught up">
          Questions from the BA agent and BRD reviews will appear here.
        </EmptyState>
      )}
      {list.data && list.data.length > 0 && (
        <Card
          title={unread.length > 0 ? `${unread.length} unread` : 'All read'}
          actions={unread.length > 0 && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void markRead(unread.map((n) => n.id))}>
              Mark all as read
            </button>
          )}
          flush
        >
          <ul className="notification-list">
            {list.data.map((n) => (
              <li key={n.id} className={n.readAt ? 'notification' : 'notification unread'}>
                <div className="notification-main">
                  {!n.readAt && <Badge tone="primary">New</Badge>}
                  <span>{KINDS[n.kind] ?? 'There is an update on one of your requirements.'}</span>
                </div>
                <div className="notification-meta">
                  <span className="small" title={formatDateTime(n.createdAt)}>{relativeTime(n.createdAt)}</span>
                  {n.requirementId && (
                    <Link
                      className="btn btn-secondary btn-sm"
                      to={n.kind === 'CLARIFICATION_ASSIGNED' ? '/clarifications' : `/requirements/${n.requirementId}`}
                      onClick={() => { if (!n.readAt) void markRead([n.id]); }}
                    >
                      {n.kind === 'CLARIFICATION_ASSIGNED' ? 'Answer' : 'Open'}
                    </Link>
                  )}
                  {!n.readAt && (
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => void markRead([n.id])}>
                      Mark as read
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </section>
  );
}
