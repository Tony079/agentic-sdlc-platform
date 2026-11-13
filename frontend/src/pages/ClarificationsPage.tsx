import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Clarification } from '../api/types';
import { ClarificationCard } from '../components/ClarificationCard';
import { PageHeader } from '../ui/components';

/** Client clarification inbox (FR-CLR, AC-CORE-14): questions assigned to the requirement owner. */
export function ClarificationsPage() {
  const queryClient = useQueryClient();
  const inbox = useQuery({
    queryKey: ['clarifications'],
    queryFn: () => api.get<Clarification[]>('/api/v1/clarifications'),
  });
  return (
    <section>
      <PageHeader title="Clarifications" description="Questions from the analysts that need an answer before the BRD can be completed." />
      {inbox.isPending && <p>Loading…</p>}
      {inbox.isError && <p role="alert">The clarifications could not be loaded.</p>}
      {inbox.data?.length === 0 && <p>No questions are waiting for you.</p>}
      {inbox.data?.map((c) => (
        <ClarificationCard
          key={c.id}
          clarification={c}
          basePath="/api/v1/clarifications"
          onChanged={() => void queryClient.invalidateQueries({ queryKey: ['clarifications'] })}
        />
      ))}
    </section>
  );
}
