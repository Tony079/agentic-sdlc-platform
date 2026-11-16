import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { money, SIZE_LABEL } from '../ui/money';
import { useToast } from '../ui/Toast';
import { Badge, Callout, Card, formatDateTime } from '../ui/components';

interface Quote {
  id: string;
  status: 'PROPOSED' | 'ACCEPTED' | 'DECLINED';
  size: string;
  estimated: boolean;
  documents: number;
  hasRepository: boolean;
  repositories: number;
  textChars: number;
  currency: string;
  listPrice: number;
  planName: string;
  includedPerMonth: number;
  includedUsedThisMonth: number;
  wouldBeIncluded: boolean;
  included: boolean | null;
  charge: number | null;
  quotedAt: string;
  acceptedByName: string | null;
  acceptedAt: string | null;
  declinedByName: string | null;
  canDecide: boolean;
}

/** GD-22: the BRD quote. Analysis starts only after the requirement's owner or a BRD Approver accepts it. */
export function BrdQuotePanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const quote = useQuery({
    queryKey: ['brd-quote', requirementId],
    queryFn: async () => {
      try {
        return await api.get<Quote>(`/api/v1/requirements/${requirementId}/brd-quote`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    // While the quote is open it follows the inputs (documents processed, repositories pinned).
    refetchInterval: (q) => (q.state.data?.status === 'PROPOSED' ? 5000 : false),
  });
  if (!quote.data?.status || !quote.data.size) return null;
  const q = quote.data;
  async function decide(action: 'accept' | 'decline') {
    try {
      await api.post(`/api/v1/requirements/${requirementId}/brd-quote/${action}`);
      toast.success(action === 'accept' ? 'Quote accepted. Analysis starts once the inputs are ready.' : 'Quote declined. Analysis will not start.');
      await queryClient.invalidateQueries({ queryKey: ['brd-quote', requirementId] });
      await queryClient.invalidateQueries({ queryKey: ['requirement', requirementId] });
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'The decision could not be saved.');
    }
  }
  const remaining = Math.max(0, q.includedPerMonth - q.includedUsedThisMonth);
  return (
    <Card
      title="BRD quote"
      actions={
        q.status === 'ACCEPTED' ? (
          <Badge tone="success">Accepted</Badge>
        ) : q.status === 'DECLINED' ? (
          <Badge tone="danger">Declined</Badge>
        ) : (
          <Badge tone="warning">Waiting for acceptance</Badge>
        )
      }
    >
      <dl className="details" style={{ gridTemplateColumns: '110px minmax(0, 1fr)' }}>
        <dt>Size</dt>
        <dd>
          {SIZE_LABEL[q.size] ?? q.size}
          {q.estimated && <span className="small"> (estimated)</span>}
        </dd>
        <dt>Based on</dt>
        <dd className="small">
          {q.documents} document{q.documents === 1 ? '' : 's'}
          {q.repositories > 0 ? `, ${q.repositories} repositor${q.repositories === 1 ? 'y' : 'ies'}` : ''},{' '}
          {(q.textChars ?? 0).toLocaleString()} characters of requirement text
        </dd>
        <dt>Price</dt>
        <dd>
          {q.status === 'ACCEPTED' ? (
            q.included ? (
              <>Included in the {q.planName} plan</>
            ) : (
              money(q.currency, q.charge ?? q.listPrice)
            )
          ) : q.wouldBeIncluded ? (
            <>
              Included in the plan <span className="small">({remaining} of {q.includedPerMonth} left this month)</span>
            </>
          ) : (
            <>
              {money(q.currency, q.listPrice)}{' '}
              <span className="small">
                ({q.includedPerMonth === 0 ? 'no BRDs included in the plan' : `all ${q.includedPerMonth} included BRDs used this month`})
              </span>
            </>
          )}
        </dd>
        {q.acceptedAt && (
          <>
            <dt>Accepted</dt>
            <dd className="small">
              {q.acceptedByName} · {formatDateTime(q.acceptedAt)}
            </dd>
          </>
        )}
      </dl>
      {q.status !== 'ACCEPTED' && q.canDecide && (
        <div className="row" style={{ marginTop: '0.75rem' }}>
          <button type="button" className="btn-primary btn-sm" onClick={() => void decide('accept')}>
            Accept quote
          </button>
          {q.status === 'PROPOSED' && (
            <button type="button" className="btn-secondary btn-sm" onClick={() => void decide('decline')}>
              Decline
            </button>
          )}
        </div>
      )}
      {q.status === 'PROPOSED' && !q.canDecide && (
        <p className="small">The requirement's owner or a BRD Approver of the project accepts the quote.</p>
      )}
      {q.status === 'PROPOSED' && (
        <Callout tone="info">The quote is updated from the inputs until it is accepted. Analysis starts after acceptance.</Callout>
      )}
    </Card>
  );
}
