import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { money } from '../ui/money';
import { Callout, Card, EmptyState, ErrorText, Loading } from '../ui/components';

interface CostRow {
  tenantId: string;
  organisationName: string;
  projectId: string | null;
  projectName: string | null;
  requirementId: string | null;
  requirementKey: string | null;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  estimatedCalls: number;
  unmeteredCalls: number;
}

interface CostReport {
  month: string;
  level: 'ORGANISATION' | 'PROJECT' | 'REQUIREMENT';
  rows: CostRow[];
  totalUsd: number;
  includesEstimates: boolean;
}

const tokens = new Intl.NumberFormat();

/** GD-22: Garuda's model spend for a month, by organisation, then project, then requirement. */
export function AiCostReport({ month }: { month: string }) {
  const [org, setOrg] = useState<{ id: string; name: string } | null>(null);
  const [project, setProject] = useState<{ id: string; name: string } | null>(null);
  const params = new URLSearchParams({ month });
  if (org) params.set('tenantId', org.id);
  if (org && project) params.set('projectId', project.id);
  const report = useQuery({
    queryKey: ['ai-costs', month, org?.id ?? null, project?.id ?? null],
    queryFn: () => api.get<CostReport>(`/api/v1/staff/billing/ai-costs?${params.toString()}`),
  });
  const level = report.data?.level ?? (project ? 'REQUIREMENT' : org ? 'PROJECT' : 'ORGANISATION');
  const label = level === 'ORGANISATION' ? 'Organisation' : level === 'PROJECT' ? 'Project' : 'Requirement';
  return (
    <Card
      title="AI cost"
      flush
      actions={
        <nav aria-label="AI cost level" className="row small">
          <button type="button" className="btn-ghost btn-sm" disabled={!org} onClick={() => { setOrg(null); setProject(null); }}>
            All organisations
          </button>
          {org && (
            <>
              <span aria-hidden>›</span>
              <button type="button" className="btn-ghost btn-sm" disabled={!project} onClick={() => setProject(null)}>
                {org.name}
              </button>
            </>
          )}
          {project && (
            <>
              <span aria-hidden>›</span>
              <strong>{project.name}</strong>
            </>
          )}
        </nav>
      }
    >
      {report.isPending && <div className="card-body"><Loading /></div>}
      {report.isError && <div className="card-body"><ErrorText>The AI cost could not be loaded.</ErrorText></div>}
      {report.data && report.data.rows.length === 0 && <EmptyState icon="sparkles" title="No model calls in this month" />}
      {report.data && report.data.rows.length > 0 && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{label}</th>
                  <th scope="col" className="num">Model calls</th>
                  <th scope="col" className="num">Input tokens</th>
                  <th scope="col" className="num">Output tokens</th>
                  <th scope="col" className="num">Cost (USD)</th>
                </tr>
              </thead>
              <tbody>
                {report.data.rows.map((r) => {
                  const name =
                    level === 'ORGANISATION' ? r.organisationName : level === 'PROJECT' ? r.projectName ?? 'Not linked to a project' : r.requirementKey ?? 'Not linked to a requirement';
                  const drill =
                    level === 'ORGANISATION'
                      ? () => setOrg({ id: r.tenantId, name: r.organisationName })
                      : level === 'PROJECT' && r.projectId
                        ? () => setProject({ id: r.projectId!, name: r.projectName ?? '' })
                        : null;
                  return (
                    <tr key={`${r.tenantId}-${r.projectId}-${r.requirementId}`}>
                      <td className="primary-cell">
                        {drill ? (
                          <button type="button" className="link" onClick={drill}>
                            {name}
                          </button>
                        ) : (
                          name
                        )}
                        {(r.estimatedCalls > 0 || r.unmeteredCalls > 0) && (
                          <span className="small" style={{ display: 'block' }}>
                            {r.estimatedCalls > 0 && `${r.estimatedCalls} estimated`}
                            {r.estimatedCalls > 0 && r.unmeteredCalls > 0 && ' · '}
                            {r.unmeteredCalls > 0 && `${r.unmeteredCalls} without usage data`}
                          </span>
                        )}
                      </td>
                      <td className="num">{tokens.format(r.calls)}</td>
                      <td className="num">{tokens.format(r.inputTokens)}</td>
                      <td className="num">{tokens.format(r.outputTokens)}</td>
                      <td className="num">{money('USD', r.costUsd, 4)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="card-footer">
            <strong>Total {money('USD', report.data.totalUsd, 4)}</strong>
          </div>
          {report.data.includesEstimates && (
            <div className="card-body">
              <Callout tone="info">
                Some calls have locally estimated usage, or none from the provider. Calls without usage data add no cost here;
                provider invoices remain the source of truth.
              </Callout>
            </div>
          )}
        </>
      )}
    </Card>
  );
}
