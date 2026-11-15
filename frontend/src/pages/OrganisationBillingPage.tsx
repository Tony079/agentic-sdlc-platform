import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { money, SIZE_LABEL } from '../ui/money';
import { Badge, Callout, Card, EmptyState, ErrorText, Loading, PageHeader, formatDate } from '../ui/components';

interface ChargeLine {
  type: 'PROJECT_FEE' | 'BRD';
  projectId: string;
  projectName: string;
  requirementKey: string | null;
  size: string | null;
  included: boolean | null;
  currency: string;
  amount: number;
  date: string;
}

interface Statement {
  month: string;
  lines: ChargeLine[];
  totals: { currency: string; amount: number }[];
  estimatedSizes: boolean;
}

/** GD-22: the organisation's charges for a month (Organisation Admins). */
export function OrganisationBillingPage() {
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const statement = useQuery({
    queryKey: ['org-billing', month],
    queryFn: () => api.get<Statement>(`/api/v1/organisation/billing?month=${month}`),
  });
  const fees = statement.data?.lines.filter((l) => l.type === 'PROJECT_FEE') ?? [];
  const brds = statement.data?.lines.filter((l) => l.type === 'BRD') ?? [];
  return (
    <>
      <PageHeader
        eyebrow="Administration"
        title="Billing"
        description="Each active project is charged monthly. Each BRD is charged by its accepted size quote, after the plan's included BRDs for the month."
        actions={<input type="month" aria-label="Month" value={month} onChange={(e) => setMonth(e.target.value)} style={{ width: 170 }} />}
      />
      {statement.isPending && <Loading />}
      {statement.isError && <ErrorText>Billing could not be loaded.</ErrorText>}
      {statement.data && (
        <div className="stack">
          <Card title="Project fees" flush>
            {fees.length === 0 ? (
              <EmptyState icon="folder" title="No active projects in this month" />
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Project</th>
                      <th scope="col">Billed to</th>
                      <th scope="col" className="num">Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {fees.map((l) => (
                      <tr key={l.projectId}>
                        <td className="primary-cell">
                          <Link to={`/projects/${l.projectId}`}>{l.projectName}</Link>
                        </td>
                        <td className="small">{formatDate(l.date)}</td>
                        <td className="num">{money(l.currency, l.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          <Card title="BRDs" flush>
            {brds.length === 0 ? (
              <EmptyState icon="file" title="No BRD quotes accepted in this month" />
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Requirement</th>
                      <th scope="col">Project</th>
                      <th scope="col">Size</th>
                      <th scope="col">Accepted</th>
                      <th scope="col" className="num">Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {brds.map((l) => (
                      <tr key={l.requirementKey}>
                        <td className="mono">{l.requirementKey}</td>
                        <td>{l.projectName}</td>
                        <td>{SIZE_LABEL[l.size ?? ''] ?? l.size}</td>
                        <td className="small">{formatDate(l.date)}</td>
                        <td className="num">{l.included ? <Badge tone="success">Included in plan</Badge> : money(l.currency, l.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          {statement.data.totals.length > 0 && (
            <Card title="Total for the month">
              {statement.data.totals.map((t) => (
                <p key={t.currency} style={{ fontSize: '1.25rem', margin: 0 }}>
                  <strong>{money(t.currency, t.amount)}</strong>
                </p>
              ))}
              <p className="small">Taxes are not included. Garuda issues the invoice.</p>
            </Card>
          )}
          {statement.data.estimatedSizes && (
            <Callout tone="info">BRD sizes are estimated from the requirement's inputs (documents, repository and text).</Callout>
          )}
        </div>
      )}
    </>
  );
}
