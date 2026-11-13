import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { DashboardOverview, DashboardRequirement } from '../../api/types';
import { Icon } from '../../ui/Icon';
import {
  Badge,
  Card,
  EmptyState,
  ErrorText,
  Loading,
  PageHeader,
  Stat,
  StatePill,
  relativeTime,
  stateLabel,
  type Tone,
} from '../../ui/components';

/** Groups workflow states into the stages shown on the distribution bar. */
const STAGES: { id: string; label: string; color: string; states: string[] }[] = [
  { id: 'intake', label: 'Intake', color: 'var(--info)', states: ['DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING'] },
  {
    id: 'client',
    label: 'Waiting for client',
    color: 'var(--warning)',
    states: ['AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION', 'AWAITING_CLARIFICATION', 'CLIENT_CHANGES_REQUESTED'],
  },
  { id: 'analysis', label: 'Analysis', color: 'var(--primary)', states: ['ANALYSING'] },
  {
    id: 'review',
    label: 'Review',
    color: 'var(--violet)',
    states: ['BRD_DRAFTED', 'INTERNAL_REVIEW', 'REVISION_REQUESTED', 'INTERNAL_APPROVED'],
  },
  { id: 'shared', label: 'BRD shared', color: 'var(--success)', states: ['SHARED_WITH_CLIENT'] },
  { id: 'closed', label: 'Closed or failed', color: 'var(--danger)', states: ['FAILED', 'REJECTED', 'CANCELLED'] },
];

export const BOTTLENECK_LABELS: Record<string, { label: string; tone: Tone }> = {
  FAILED: { label: 'Failed', tone: 'danger' },
  WAITING_FOR_CLIENT: { label: 'Waiting for client', tone: 'warning' },
  WAITING_FOR_REVIEW: { label: 'Waiting for review', tone: 'violet' },
  WAITING_FOR_RELEASE: { label: 'Waiting for release', tone: 'primary' },
  WAITING_FOR_INTAKE: { label: 'Waiting for intake', tone: 'info' },
};

export function BottleneckTable({ rows, showOrganisation = true }: { rows: DashboardRequirement[]; showOrganisation?: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">Requirement</th>
            {showOrganisation && <th scope="col">Organisation</th>}
            <th scope="col">Project</th>
            <th scope="col">State</th>
            <th scope="col">Why it is listed</th>
            <th scope="col" className="num">
              Days in state
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={`${r.organisationId}-${r.key}`}>
              <td>
                <span className="primary-cell">{r.title}</span>
                <span className="sub mono">{r.key}</span>
              </td>
              {showOrganisation && (
                <td>
                  <Link to={`/staff/organisations/${r.organisationId}`}>{r.organisationName}</Link>
                </td>
              )}
              <td>{r.projectName}</td>
              <td>
                <StatePill state={r.state} />
              </td>
              <td>
                {r.bottleneck ? (
                  <Badge tone={BOTTLENECK_LABELS[r.bottleneck]?.tone ?? 'neutral'}>{BOTTLENECK_LABELS[r.bottleneck]?.label}</Badge>
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td className="num">{r.daysInState}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Garuda dashboard across every client organisation (FR-DSH-01..03; titles but no content, GD-07). */
export function StaffDashboardPage() {
  const overview = useQuery({
    queryKey: ['staff-dashboard'],
    queryFn: () => api.get<DashboardOverview>('/api/v1/staff/platform/dashboard'),
    refetchInterval: 60_000,
  });
  if (overview.isPending) return <Loading label="Loading the dashboard…" />;
  if (overview.isError || !overview.data) return <ErrorText>The dashboard could not be loaded.</ErrorText>;
  const d = overview.data;
  const stageCounts = STAGES.map((s) => ({
    ...s,
    count: s.states.reduce((sum, st) => sum + (d.requirementsByState[st] ?? 0), 0),
  }));
  const total = stageCounts.reduce((sum, s) => sum + s.count, 0);

  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Dashboard"
        description="Every client organisation at a glance. Requirement titles are shown; descriptions, documents and BRD content are not."
        actions={
          <Link to="/staff/onboarding" className="btn btn-primary">
            <Icon name="plus" size={16} />
            Onboard organisation
          </Link>
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat
          icon="building"
          label="Organisations"
          value={d.organisations}
          meta={`${d.activeOrganisations} active${d.suspendedOrganisations ? `, ${d.suspendedOrganisations} suspended` : ''}`}
        />
        <Stat icon="users" label="Active users" value={d.users} meta={`${d.projects} projects`} />
        <Stat icon="file" label="Requirements" value={d.requirements} meta={`${d.repositories} repositories linked`} />
        <Stat icon="alert" label="Bottlenecks" value={d.bottlenecks.length} meta={`Stuck ${d.staleDays}+ days or failed`} />
      </div>

      <Card title="Requirements by stage">
        {total === 0 ? (
          <p className="muted">No requirements yet.</p>
        ) : (
          <>
            <div className="dist" role="img" aria-label="Requirements by stage">
              {stageCounts
                .filter((s) => s.count > 0)
                .map((s) => (
                  <span key={s.id} style={{ width: `${(s.count / total) * 100}%`, background: s.color }} title={`${s.label}: ${s.count}`} />
                ))}
            </div>
            <div className="legend">
              {stageCounts.map((s) => (
                <span key={s.id}>
                  <i style={{ background: s.color }} />
                  {s.label} <strong>{s.count}</strong>
                </span>
              ))}
            </div>
            <details style={{ marginTop: '0.75rem' }}>
              <summary className="small" style={{ cursor: 'pointer' }}>
                Counts by state
              </summary>
              <div className="row" style={{ marginTop: '0.6rem' }}>
                {Object.entries(d.requirementsByState)
                  .sort((a, b) => b[1] - a[1])
                  .map(([state, count]) => (
                    <span key={state} className="badge tone-neutral no-dot">
                      {stateLabel(state)} · {count}
                    </span>
                  ))}
              </div>
            </details>
          </>
        )}
      </Card>

      <Card
        title="Organisations"
        flush
        actions={
          <Link to="/staff/organisations" className="btn btn-ghost btn-sm">
            View all
            <Icon name="arrowRight" size={15} />
          </Link>
        }
      >
        {d.organisationRows.length === 0 ? (
          <EmptyState icon="building" title="No organisations yet" action={<Link to="/staff/onboarding" className="btn btn-primary">Onboard the first one</Link>} />
        ) : (
          <OrganisationTable rows={d.organisationRows.slice(0, 10)} />
        )}
      </Card>

      <Card title="Bottlenecks" flush>
        {d.bottlenecks.length === 0 ? (
          <EmptyState icon="checkCircle" title="Nothing is stuck">
            No requirement has failed or waited {d.staleDays} days or more in one state.
          </EmptyState>
        ) : (
          <BottleneckTable rows={d.bottlenecks} />
        )}
      </Card>
    </>
  );
}

export function OrganisationTable({ rows }: { rows: DashboardOverview['organisationRows'] }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">Organisation</th>
            <th scope="col">Status</th>
            <th scope="col">Organisation Admins</th>
            <th scope="col" className="num">
              Users
            </th>
            <th scope="col" className="num">
              Projects
            </th>
            <th scope="col" className="num">
              Open requirements
            </th>
            <th scope="col" className="num">
              Bottlenecks
            </th>
            <th scope="col">Last activity</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((o) => (
            <tr key={o.id}>
              <td>
                <Link to={`/staff/organisations/${o.id}`} className="primary-cell" style={{ color: 'var(--text)' }}>
                  {o.name}
                </Link>
                <span className="sub mono">{o.slug}</span>
              </td>
              <td>{o.status === 'ACTIVE' ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Suspended</Badge>}</td>
              <td>
                {o.admins.length === 0 ? (
                  <span className="muted small">None</span>
                ) : (
                  o.admins.slice(0, 2).map((a) => (
                    <div key={a.email} className="small">
                      {a.displayName} <span className="muted">· {a.email}</span>
                      {a.status === 'INVITED' && <span className="muted"> (invited)</span>}
                    </div>
                  ))
                )}
              </td>
              <td className="num">
                {o.activeUsers}
                {o.invitedUsers > 0 && <span className="sub">+{o.invitedUsers} invited</span>}
              </td>
              <td className="num">{o.projects}</td>
              <td className="num">{o.openRequirements}</td>
              <td className="num">{o.bottlenecks > 0 ? <Badge tone="warning">{o.bottlenecks}</Badge> : <span className="muted">0</span>}</td>
              <td className="small">{relativeTime(o.lastActivityAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
