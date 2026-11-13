import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { ChangeRequest, StaffMe, TenantLifecycleRequest } from '../../api/types';
import { ScopeDiffTable } from '../../components/ScopeDiffTable';
import { PageHeader } from '../../ui/components';

interface ManualIntakeEntry {
  id: string;
  key: string;
  title: string;
  currentRevisionNo: number;
  repositoryLinkAuthorised: boolean;
}

interface PendingRelease {
  requirementKey: string;
  brdVersionId: string;
  versionNo: number;
  contentHash: string;
  commitSha: string;
  gates: { name: string; passed: boolean; detail: string | null }[];
}

interface DeadJob {
  id: string;
  jobType: string;
  lastErrorCode: string | null;
  deadReason: string;
  attempts: number;
  retryable: boolean;
}

interface Flag {
  name: string;
  environment: string;
  value: string;
  protectedFlag: boolean;
  description: string;
}

interface FlagChange {
  id: string;
  name: string;
  previousValue: string;
  newValue: string;
  reason: string;
}

function useAction() {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  async function run(action: () => Promise<unknown>, success: string) {
    setMessage(null);
    try {
      await action();
      setMessage(success);
      await queryClient.invalidateQueries();
    } catch (e) {
      setMessage(e instanceof ApiError ? `${e.code}: ${e.message}` : 'The action failed.');
    }
  }
  return { message, run };
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      {children}
    </section>
  );
}

function ReasonButton({ label, onSubmit }: { label: string; onSubmit: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  return (
    <span className="inline-form">
      <input aria-label={`${label} reason`} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} />
      <button type="button" disabled={reason.trim().length < 10} onClick={() => onSubmit(reason.trim())}>
        {label}
      </button>
    </span>
  );
}

function ManualIntake({ run }: { run: ReturnType<typeof useAction>['run'] }) {
  const list = useQuery({ queryKey: ['manual-intake'], queryFn: () => api.get<ManualIntakeEntry[]>('/api/v1/staff/manual-intake') });
  return (
    <Section title="Manual intake">
      {list.data?.length === 0 && <p>No requirement is in manual intake.</p>}
      <ul>
        {list.data?.map((r) => (
          <li key={r.id}>
            {r.key}: {r.title} · revision {r.currentRevisionNo} ·{' '}
            {r.repositoryLinkAuthorised ? 'repository authorised' : 'no authorised repository yet'}{' '}
            <ReasonButton
              label="Resume"
              onSubmit={(reason) =>
                void run(
                  () => api.post(`/api/v1/staff/requirements/${r.id}/resume`, { expectedRevisionNo: r.currentRevisionNo, reason }),
                  `${r.key} resumed; repository validation starts.`,
                )
              }
            />{' '}
            <ReasonButton
              label="Close"
              onSubmit={(reason) => void run(() => api.post(`/api/v1/staff/requirements/${r.id}/close`, { reason }), `${r.key} closed.`)}
            />
          </li>
        ))}
      </ul>
    </Section>
  );
}

function ChangeRequests({ me, run }: { me: StaffMe; run: ReturnType<typeof useAction>['run'] }) {
  const list = useQuery({ queryKey: ['staff-change-requests'], queryFn: () => api.get<ChangeRequest[]>('/api/v1/staff/change-requests') });
  const [assessment, setAssessment] = useState({ scopeAssessment: '', effortEstimate: '', timelineImpact: '', technicalImpact: '' });
  const analyst = me.capabilities.includes('BUSINESS_ANALYST');
  const reviewer = me.capabilities.includes('BUSINESS_REVIEWER');
  return (
    <Section title="Change requests">
      {list.data?.length === 0 && <p>No change request to assess or review.</p>}
      {list.data?.map((c) => (
        <article key={c.id} className="card">
          <h3>{c.requestedChange}</h3>
          <p className="small">{c.status}</p>
          <ScopeDiffTable diff={c.diff} />
          {analyst && c.status === 'SUBMITTED' && (
            <div className="actions">
              {(['scopeAssessment', 'effortEstimate', 'timelineImpact', 'technicalImpact'] as const).map((field) => (
                <label key={field}>
                  {field}
                  <input value={assessment[field]} onChange={(e) => setAssessment({ ...assessment, [field]: e.target.value })} />
                </label>
              ))}
              <button
                type="button"
                onClick={() => void run(() => api.post(`/api/v1/staff/change-requests/${c.id}/assessment`, assessment), 'Assessment saved.')}
              >
                Save assessment
              </button>
            </div>
          )}
          {reviewer && c.status === 'ASSESSED' && (
            <button
              type="button"
              onClick={() =>
                void run(() => api.post(`/api/v1/staff/change-requests/${c.id}/review`, { decision: 'APPROVE' }), 'Sent to the Client Approver.')
              }
            >
              Approve assessment
            </button>
          )}
        </article>
      ))}
    </Section>
  );
}

function Releases({ run }: { run: ReturnType<typeof useAction>['run'] }) {
  const list = useQuery({ queryKey: ['releases'], queryFn: () => api.get<PendingRelease[]>('/api/v1/staff/releases') });
  return (
    <Section title="Releases awaiting approval">
      {list.data?.length === 0 && <p>No approved BRD awaits release.</p>}
      {list.data?.map((r) => {
        const passed = r.gates.every((g) => g.passed);
        return (
          <article key={r.brdVersionId} className="card">
            <h3>
              {r.requirementKey} · BRD v{r.versionNo} · commit {r.commitSha.substring(0, 12)}
            </h3>
            <ul>
              {r.gates.map((g) => (
                <li key={g.name}>
                  {g.passed ? 'PASS' : 'FAIL'} {g.name} {g.detail ?? ''}
                </li>
              ))}
            </ul>
            {passed ? (
              <ReasonButton
                label="Approve release"
                onSubmit={(reason) =>
                  void run(
                    () =>
                      api.post(`/api/v1/staff/brd-versions/${r.brdVersionId}/release`, {
                        expectedContentHash: r.contentHash,
                        expectedCommitSha: r.commitSha,
                        reason,
                      }),
                    'Release approved; the worker releases it after re-checking the gates.',
                  )
                }
              />
            ) : (
              <p role="alert">A mandatory gate failed; the release cannot be approved.</p>
            )}
          </article>
        );
      })}
    </Section>
  );
}

function FailedJobs({ run }: { run: ReturnType<typeof useAction>['run'] }) {
  const list = useQuery({ queryKey: ['failed-jobs'], queryFn: () => api.get<DeadJob[]>('/api/v1/staff/failed-jobs') });
  return (
    <Section title="Failed jobs">
      {list.data?.length === 0 && <p>No failed jobs.</p>}
      <ul>
        {list.data?.map((j) => (
          <li key={j.id}>
            {j.jobType} · {j.deadReason} · {j.lastErrorCode ?? '—'} · {j.attempts} attempt(s){' '}
            {j.retryable ? (
              <ReasonButton
                label="Retry"
                onSubmit={(reason) => void run(() => api.post(`/api/v1/staff/failed-jobs/${j.id}/retry`, { reason }), 'Retry requested.')}
              />
            ) : (
              <span className="small">permanent or no approved retry path</span>
            )}
          </li>
        ))}
      </ul>
    </Section>
  );
}

function TenantLifecycle({ run }: { run: ReturnType<typeof useAction>['run'] }) {
  const list = useQuery({
    queryKey: ['tenant-lifecycle'],
    queryFn: () => api.get<TenantLifecycleRequest[]>('/api/v1/staff/tenant-lifecycle-requests'),
  });
  return (
    <Section title="Organisation deletion and restoration">
      {list.data?.length === 0 && <p>No requests.</p>}
      <ul>
        {list.data?.map((r) => (
          <li key={r.id}>
            {r.tenantName} · {r.kind} · {r.status} · {r.reason}{' '}
            {r.status === 'REQUESTED' && (
              <button type="button" onClick={() => void run(() => api.post(`/api/v1/staff/tenant-lifecycle-requests/${r.id}/verify`, {}), 'Verified.')}>
                Verify
              </button>
            )}
            {r.status === 'VERIFIED' && (
              <button
                type="button"
                onClick={() => void run(() => api.post(`/api/v1/staff/tenant-lifecycle-requests/${r.id}/approve`, {}), 'Approved and executed.')}
              >
                Approve (second person)
              </button>
            )}
          </li>
        ))}
      </ul>
    </Section>
  );
}

function FeatureFlags({ run }: { run: ReturnType<typeof useAction>['run'] }) {
  const flags = useQuery({ queryKey: ['flags'], queryFn: () => api.get<Flag[]>('/api/v1/staff/feature-flags') });
  const overview = useQuery({
    queryKey: ['operations'],
    queryFn: () => api.get<{ unacknowledgedFlagChanges: FlagChange[] }>('/api/v1/staff/operations'),
  });
  return (
    <Section title="Feature flags">
      <ul>
        {flags.data?.map((f) => (
          <li key={`${f.name}@${f.environment}`}>
            <code>{f.name}</code> = {f.value} {f.protectedFlag && <span className="small">(protected: security or release control)</span>}{' '}
            {!f.protectedFlag && (
              <ReasonButton
                label={`Set ${f.name} to ${f.value === 'on' ? 'off' : 'on'}`}
                onSubmit={(reason) =>
                  void run(
                    () => api.put(`/api/v1/staff/feature-flags/${f.name}/${f.environment}`, { value: f.value === 'on' ? 'off' : 'on', reason }),
                    'Flag changed; another operator must acknowledge it.',
                  )
                }
              />
            )}
          </li>
        ))}
      </ul>
      <h3>Unacknowledged changes</h3>
      {overview.data?.unacknowledgedFlagChanges.length === 0 && <p>None.</p>}
      <ul>
        {overview.data?.unacknowledgedFlagChanges.map((c) => (
          <li key={c.id}>
            {c.name}: {c.previousValue} → {c.newValue} ({c.reason}){' '}
            <button type="button" onClick={() => void run(() => api.post(`/api/v1/staff/feature-flag-changes/${c.id}/acknowledge`, {}), 'Acknowledged.')}>
              Acknowledge
            </button>
          </li>
        ))}
      </ul>
    </Section>
  );
}

/** Staff operations by capability (owner decisions 2026-11-08). Sections are hidden without the capability; the server decides. */
export function StaffOperationsPage() {
  const me = useQuery({ queryKey: ['staff-me'], queryFn: () => api.get<StaffMe>('/api/v1/staff/me'), retry: false });
  const { message, run } = useAction();
  if (me.isPending) return <p>Loading…</p>;
  if (!me.data) return <p role="alert">Sign in as staff first.</p>;
  const has = (c: string) => me.data.capabilities.includes(c);
  const tenant = !!me.data.activeTenant;
  return (
    <section>
      <p>
        <Link to="/staff">Staff workspace</Link>
      </p>
      <PageHeader eyebrow="Platform" title="Operations" />
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      {tenant && has('BUSINESS_REVIEWER') && <ManualIntake run={run} />}
      {tenant && (has('BUSINESS_ANALYST') || has('BUSINESS_REVIEWER')) && <ChangeRequests me={me.data} run={run} />}
      {tenant && has('RELEASE_APPROVE') && <Releases run={run} />}
      {tenant && has('JOB_RETRY') && <FailedJobs run={run} />}
      {has('TENANT_DELETE') && <TenantLifecycle run={run} />}
      {has('FEATURE_FLAG_ACK') && <FeatureFlags run={run} />}
    </section>
  );
}
