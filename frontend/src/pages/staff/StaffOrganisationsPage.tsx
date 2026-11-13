import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { DashboardOverview, OrganisationDetail, OrganisationStatusRequest } from '../../api/types';
import { hasAny, useStaffMe } from '../../components/StaffLayout';
import { Icon } from '../../ui/Icon';
import { useToast } from '../../ui/Toast';
import {
  BackLink,
  Badge,
  Card,
  EmptyState,
  ErrorText,
  Loading,
  Modal,
  PageHeader,
  Stat,
  StatePill,
  Tabs,
  formatDate,
  relativeTime,
} from '../../ui/components';
import { BottleneckTable, OrganisationTable } from './StaffDashboardPage';

export function StaffOrganisationsPage() {
  const overview = useQuery({
    queryKey: ['staff-dashboard'],
    queryFn: () => api.get<DashboardOverview>('/api/v1/staff/platform/dashboard'),
  });
  const [search, setSearch] = useState('');
  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (overview.data?.organisationRows ?? []).filter((o) => !q || o.name.toLowerCase().includes(q) || o.slug.includes(q));
  }, [overview.data, search]);
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Organisations"
        description="Client organisations on Garuda, their administrators and activity."
        actions={
          <Link to="/staff/onboarding" className="btn btn-primary">
            <Icon name="plus" size={16} />
            Onboard organisation
          </Link>
        }
      />
      <div className="card card-flush">
        <div className="card-header">
          <span className="small">{overview.data ? `${rows.length} organisations` : ''}</span>
          <input
            type="search"
            placeholder="Search organisations"
            aria-label="Search organisations"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ maxWidth: 280 }}
          />
        </div>
        {overview.isPending && <div className="card-body"><Loading /></div>}
        {overview.isError && <div className="card-body"><ErrorText>Organisations could not be loaded.</ErrorText></div>}
        {overview.data && rows.length === 0 && <EmptyState icon="building" title="No organisations found" />}
        {rows.length > 0 && <OrganisationTable rows={rows} />}
      </div>
    </>
  );
}

function StatusRequestModal({
  organisation,
  kind,
  onClose,
}: {
  organisation: OrganisationDetail['organisation'];
  kind: 'SUSPEND' | 'REACTIVATE';
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState('');
  const request = useMutation({
    mutationFn: () => api.post<OrganisationStatusRequest>(`/api/v1/staff/organisations/${organisation.id}/status-requests`, { kind, reason: reason.trim() }),
    onSuccess: async () => {
      toast.success('Request recorded. A second Garuda administrator must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['staff-status-requests'] });
      onClose();
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    request.mutate();
  }
  const suspend = kind === 'SUSPEND';
  return (
    <Modal
      title={suspend ? `Suspend ${organisation.name}` : `Reactivate ${organisation.name}`}
      description={
        suspend
          ? 'Once approved by a second administrator, everyone in the organisation is signed out and cannot sign in. No data is deleted.'
          : 'Once approved by a second administrator, the organisation’s users can sign in again.'
      }
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            form="status-form"
            className={suspend ? 'btn-danger' : undefined}
            disabled={reason.trim().length < 10 || request.isPending}
          >
            Request {suspend ? 'suspension' : 'reactivation'}
          </button>
        </>
      }
    >
      <form id="status-form" onSubmit={submit}>
        <label>
          Reason
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={2000} rows={3} />
          <span className="hint">At least 10 characters. Recorded in the platform audit history.</span>
        </label>
        {request.isError && (
          <ErrorText>
            {request.error instanceof ApiError && request.error.code === 'STATUS_REQUEST_OPEN'
              ? 'This organisation already has an open request.'
              : request.error instanceof ApiError
                ? request.error.message
                : 'The request could not be recorded.'}
          </ErrorText>
        )}
      </form>
    </Modal>
  );
}

export function StaffOrganisationDetailPage() {
  const { id = '' } = useParams();
  const me = useStaffMe();
  const [tab, setTab] = useState<'requirements' | 'projects' | 'bottlenecks'>('requirements');
  const [statusKind, setStatusKind] = useState<'SUSPEND' | 'REACTIVATE' | null>(null);
  const detail = useQuery({
    queryKey: ['staff-organisation', id],
    queryFn: () => api.get<OrganisationDetail>(`/api/v1/staff/platform/organisations/${id}`),
  });
  if (detail.isPending) return <Loading />;
  if (detail.isError || !detail.data) {
    return (
      <>
        <BackLink to="/staff/organisations">Organisations</BackLink>
        <ErrorText>The organisation could not be loaded.</ErrorText>
      </>
    );
  }
  const { organisation: o, projects, requirements } = detail.data;
  const stuck = requirements.filter((r) => r.bottleneck);
  const canOnboard = hasAny(me.data, 'ORG_ONBOARD');
  return (
    <>
      <BackLink to="/staff/organisations">Organisations</BackLink>
      <PageHeader
        eyebrow={<span className="mono">{o.slug}</span>}
        title={
          <span className="row" style={{ gap: '0.75rem' }}>
            {o.name}
            {o.status === 'ACTIVE' ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Suspended</Badge>}
          </span>
        }
        description={`Onboarded ${formatDate(o.createdAt)} · last activity ${relativeTime(o.lastActivityAt)}`}
        actions={
          canOnboard &&
          (o.status === 'ACTIVE' ? (
            <button type="button" className="btn-secondary" onClick={() => setStatusKind('SUSPEND')}>
              <Icon name="pause" size={16} />
              Suspend
            </button>
          ) : (
            <button type="button" className="btn-primary" onClick={() => setStatusKind('REACTIVATE')}>
              <Icon name="play" size={16} />
              Reactivate
            </button>
          ))
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat icon="users" label="Active users" value={o.activeUsers} meta={o.invitedUsers ? `${o.invitedUsers} invited` : undefined} />
        <Stat icon="folder" label="Projects" value={o.projects} meta={`${o.repositories} repositories`} />
        <Stat icon="file" label="Open requirements" value={o.openRequirements} meta={`${o.requirements} in total`} />
        <Stat icon="alert" label="Bottlenecks" value={o.bottlenecks} />
      </div>
      <Card title="Organisation Admins" flush>
        {o.admins.length === 0 ? (
          <EmptyState icon="shield" title="No Organisation Admin" />
        ) : (
          <ul className="list">
            {o.admins.map((a) => (
              <li key={a.email}>
                <Icon name="shield" />
                <div className="grow">
                  <div className="title">{a.displayName}</div>
                  <span className="small">{a.email}</span>
                </div>
                {a.status === 'INVITED' ? <Badge tone="warning">Invited</Badge> : <Badge tone="success">Active</Badge>}
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Tabs
        label="Organisation sections"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'requirements', label: 'Requirements', count: requirements.length },
          { id: 'projects', label: 'Projects', count: projects.length },
          { id: 'bottlenecks', label: 'Bottlenecks', count: stuck.length },
        ]}
      />
      {tab === 'requirements' && (
        <div className="card card-flush">
          {requirements.length === 0 ? (
            <EmptyState icon="file" title="No requirements yet" />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Requirement</th>
                    <th scope="col">Project</th>
                    <th scope="col">State</th>
                    <th scope="col">Created</th>
                    <th scope="col" className="num">
                      Days in state
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {requirements.map((r) => (
                    <tr key={r.key}>
                      <td>
                        <span className="primary-cell">{r.title}</span>
                        <span className="sub mono">{r.key}</span>
                      </td>
                      <td>{r.projectName}</td>
                      <td>
                        <StatePill state={r.state} />
                      </td>
                      <td className="small">{formatDate(r.createdAt)}</td>
                      <td className="num">{r.daysInState}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
      {tab === 'projects' && (
        <div className="card card-flush">
          {projects.length === 0 ? (
            <EmptyState icon="folder" title="No projects yet" />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Project</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="num">
                      Members
                    </th>
                    <th scope="col" className="num">
                      Requirements
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {projects.map((p) => (
                    <tr key={p.id}>
                      <td className="primary-cell">{p.name}</td>
                      <td>{p.status === 'ACTIVE' ? <Badge tone="success">Active</Badge> : <Badge tone="neutral">Archived</Badge>}</td>
                      <td className="num">{p.members}</td>
                      <td className="num">{p.requirements}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
      {tab === 'bottlenecks' && (
        <div className="card card-flush">
          {stuck.length === 0 ? <EmptyState icon="checkCircle" title="Nothing is stuck" /> : <BottleneckTable rows={stuck} showOrganisation={false} />}
        </div>
      )}
      {statusKind && <StatusRequestModal organisation={o} kind={statusKind} onClose={() => setStatusKind(null)} />}
    </>
  );
}
