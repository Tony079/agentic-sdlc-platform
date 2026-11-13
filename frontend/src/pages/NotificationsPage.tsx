import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { NotificationItem } from '../api/types';
import { PageHeader } from '../ui/components';

const KINDS: Record<string, string> = {
  BRD_RELEASED: 'A business requirements document was shared with you.',
};

/** In-app notifications only (PRD AC-EXT-40). */
export function NotificationsPage() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['notifications'], queryFn: () => api.get<NotificationItem[]>('/api/v1/notifications') });

  async function markRead(id: string) {
    await api.post(`/api/v1/notifications/${id}/read`);
    await queryClient.invalidateQueries({ queryKey: ['notifications'] });
  }

  return (
    <section>
      <PageHeader title="Notifications" description="Updates on the requirements you work on." />
      {list.isPending && <p>Loading…</p>}
      {list.data?.length === 0 && <p>No notifications.</p>}
      <ul>
        {list.data?.map((n) => (
          <li key={n.id} className={n.readAt ? '' : 'unread'}>
            {KINDS[n.kind] ?? n.kind}{' '}
            {n.requirementId && <Link to={`/requirements/${n.requirementId}`}>Open requirement</Link>} ·{' '}
            {new Date(n.createdAt).toLocaleString()}{' '}
            {!n.readAt && (
              <button type="button" onClick={() => void markRead(n.id)}>
                Mark as read
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
