import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { useStaffMe } from '../../components/StaffLayout';
import { Icon } from '../../ui/Icon';
import { useToast } from '../../ui/Toast';
import { AiCostReport } from '../../components/AiCostReport';
import { money, SIZE_LABEL } from '../../ui/money';
import { Badge, Callout, Card, EmptyState, ErrorText, Loading, Modal, PageHeader, Stat, Tabs, formatDate, relativeTime, type Tone } from '../../ui/components';

interface Plan {
  id: string;
  name: string;
  currency: string;
  monthlyFee: number;
  includedBrds: number;
  priceSmall: number;
  priceMedium: number;
  priceLarge: number;
  status: 'PROPOSED' | 'ACTIVE' | 'REJECTED' | 'RETIRED';
  proposedBy: string;
  proposedByName: string;
  proposedAt: string;
  approvedByName: string | null;
}

interface Decision {
  id: string;
  decision: 'ACTIVATE' | 'DECLINE';
  planId: string | null;
  planName: string | null;
  note: string | null;
  status: string;
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
}

interface PendingProject {
  tenantId: string;
  organisationName: string;
  projectId: string;
  name: string;
  description: string | null;
  createdAt: string;
  members: number;
  openDecision: Decision | null;
}

interface ReportLine {
  organisationName: string;
  projectName: string;
  planName: string;
  currency: string;
  monthlyFee: number;
  billedFrom: string;
  billedTo: string;
  activeDays: number;
  daysInMonth: number;
  projectFee: number;
}

const PLAN_TONE: Record<string, Tone> = { PROPOSED: 'warning', ACTIVE: 'success', REJECTED: 'danger', RETIRED: 'neutral' };

interface BrdLine {
  organisationName: string;
  projectName: string;
  requirementId: string;
  requirementKey: string;
  size: string;
  sizingMethod: string;
  included: boolean;
  currency: string;
  listPrice: number;
  charge: number;
  acceptedOn: string;
}

interface Report {
  month: string;
  lines: ReportLine[];
  brds: BrdLine[];
  totals: { currency: string; amount: number }[];
}

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'SECOND_APPROVER_REQUIRED') return 'A different Garuda Admin must approve this.';
    if (error.status === 409 || error.status === 422) return error.message;
    if (error.status === 403) return 'Only Garuda Admins can do this.';
  }
  return fallback;
}

function PlanModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [form, setForm] = useState({ name: '', currency: 'INR', monthlyFee: '', includedBrds: '', priceSmall: '', priceMedium: '', priceLarge: '' });
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }));
  const create = useMutation({
    mutationFn: () =>
      api.post('/api/v1/staff/billing/plans', {
        name: form.name.trim(),
        currency: form.currency,
        monthlyFee: Number(form.monthlyFee),
        includedBrds: Number(form.includedBrds),
        priceSmall: Number(form.priceSmall),
        priceMedium: Number(form.priceMedium),
        priceLarge: Number(form.priceLarge),
      }),
    onSuccess: async () => {
      toast.success('Plan proposed. A second Garuda Admin must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['billing-plans'] });
      onClose();
    },
  });
  const ready = form.name.trim() && [form.monthlyFee, form.includedBrds, form.priceSmall, form.priceMedium, form.priceLarge].every((v) => v !== '' && Number(v) >= 0);
  const num = (k: keyof typeof form, label: string, hint?: string) => (
    <label>
      {label}
      <input type="number" min={0} step="0.01" value={form[k]} onChange={(e) => set(k, e.target.value)} />
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
  return (
    <Modal
      title="Propose a billing plan"
      description="Plans cannot be edited once approved; retire a plan and propose a new one instead."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="plan-form" disabled={!ready || create.isPending}>
            Propose plan
          </button>
        </>
      }
    >
      <form id="plan-form" onSubmit={(e: FormEvent) => { e.preventDefault(); create.mutate(); }}>
        <div className="form-grid">
          <label>
            Plan name
            <input value={form.name} onChange={(e) => set('name', e.target.value)} maxLength={100} />
          </label>
          <label>
            Currency
            <select value={form.currency} onChange={(e) => set('currency', e.target.value)}>
              {['INR', 'USD', 'EUR', 'GBP'].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </label>
          {num('monthlyFee', 'Monthly fee per active project')}
          {num('includedBrds', 'BRDs included per month', 'Per project. Further BRDs are charged by size.')}
          {num('priceSmall', 'Price per Small BRD')}
          {num('priceMedium', 'Price per Medium BRD')}
          {num('priceLarge', 'Price per Large BRD')}
        </div>
        {create.isError && <ErrorText>{message(create.error, 'The plan could not be proposed.')}</ErrorText>}
      </form>
    </Modal>
  );
}

function DecisionModal({ project, plans, onClose }: { project: PendingProject; plans: Plan[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [decision, setDecision] = useState<'ACTIVATE' | 'DECLINE'>('ACTIVATE');
  const [planId, setPlanId] = useState('');
  const [note, setNote] = useState('');
  const active = plans.filter((p) => p.status === 'ACTIVE');
  const propose = useMutation({
    mutationFn: () =>
      api.post('/api/v1/staff/project-requests', {
        tenantId: project.tenantId,
        projectId: project.projectId,
        decision,
        planId: decision === 'ACTIVATE' ? planId : null,
        note: note.trim() || null,
      }),
    onSuccess: async () => {
      toast.success('Decision proposed. A second Garuda Admin must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['project-requests'] });
      onClose();
    },
  });
  return (
    <Modal
      title={`${project.name} · ${project.organisationName}`}
      description="Activation starts billing from the moment the second Garuda Admin approves."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            form="decision-form"
            className={decision === 'DECLINE' ? 'btn-danger' : undefined}
            disabled={propose.isPending || (decision === 'ACTIVATE' && !planId)}
          >
            Propose {decision === 'ACTIVATE' ? 'activation' : 'decline'}
          </button>
        </>
      }
    >
      <form id="decision-form" onSubmit={(e: FormEvent) => { e.preventDefault(); propose.mutate(); }}>
        <fieldset className="field">
          <legend>Decision</legend>
          <label className="check">
            <input type="radio" checked={decision === 'ACTIVATE'} onChange={() => setDecision('ACTIVATE')} />
            Activate with a plan
          </label>
          <label className="check">
            <input type="radio" checked={decision === 'DECLINE'} onChange={() => setDecision('DECLINE')} />
            Decline (final)
          </label>
        </fieldset>
        {decision === 'ACTIVATE' &&
          (active.length === 0 ? (
            <Callout tone="warning">No approved plan yet. Propose one under Plans and have it approved first.</Callout>
          ) : (
            <label>
              Billing plan
              <select value={planId} onChange={(e) => setPlanId(e.target.value)}>
                <option value="">Choose…</option>
                {active.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} · {money(p.currency, p.monthlyFee)}/month · {p.includedBrds} BRDs included
                  </option>
                ))}
              </select>
            </label>
          ))}
        <label>
          Note
          <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={2000} placeholder="Order form reference, terms…" />
        </label>
        {propose.isError && <ErrorText>{message(propose.error, 'The decision could not be proposed.')}</ErrorText>}
      </form>
    </Modal>
  );
}

/** Billing (GD-19, GD-22): project approvals, plans and the monthly report. Every decision needs two Garuda Admins. */
export function StaffBillingPage() {
  const me = useStaffMe();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [tab, setTab] = useState<'projects' | 'plans' | 'report' | 'ai'>('projects');
  const [proposing, setProposing] = useState(false);
  const [deciding, setDeciding] = useState<PendingProject | null>(null);
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const plans = useQuery({ queryKey: ['billing-plans'], queryFn: () => api.get<Plan[]>('/api/v1/staff/billing/plans') });
  const pending = useQuery({ queryKey: ['project-requests'], queryFn: () => api.get<PendingProject[]>('/api/v1/staff/project-requests') });
  const report = useQuery({
    queryKey: ['billing-report', month],
    queryFn: () => api.get<Report>(`/api/v1/staff/billing/report?month=${month}`),
    enabled: tab === 'report',
  });
  const myId = me.data?.staffId;

  async function act(path: string, success: string, keys: string[]) {
    try {
      await api.post(path);
      toast.success(success);
      for (const k of keys) await queryClient.invalidateQueries({ queryKey: [k] });
    } catch (e) {
      toast.error(message(e, 'The action failed.'));
    }
  }

  const waiting = (pending.data ?? []).filter((p) => p.openDecision && p.openDecision.requestedBy !== myId).length
    + (plans.data ?? []).filter((p) => p.status === 'PROPOSED' && p.proposedBy !== myId).length;
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Billing"
        description="Projects are billed monthly from activation until archiving, and each BRD by its accepted size quote."
        actions={
          <button type="button" className="btn-primary" onClick={() => setProposing(true)}>
            <Icon name="plus" size={16} />
            Propose plan
          </button>
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat icon="folder" label="Projects awaiting approval" value={pending.data?.length ?? '–'} />
        <Stat icon="checkCircle" label="Waiting for your approval" value={waiting} />
        <Stat icon="layers" label="Active plans" value={(plans.data ?? []).filter((p) => p.status === 'ACTIVE').length} />
        <Stat icon="clock" label="Proposed plans" value={(plans.data ?? []).filter((p) => p.status === 'PROPOSED').length} />
      </div>
      <Tabs
        label="Billing sections"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'projects', label: 'Project approvals', count: pending.data?.length },
          { id: 'plans', label: 'Plans', count: plans.data?.length },
          { id: 'report', label: 'Monthly report' },
          { id: 'ai', label: 'AI cost' },
        ]}
      />
      {tab === 'projects' && (
        <Card title="Projects awaiting approval" flush>
          {pending.isPending && <div className="card-body"><Loading /></div>}
          {pending.isError && <div className="card-body"><ErrorText>Projects could not be loaded.</ErrorText></div>}
          {pending.data && pending.data.length === 0 && <EmptyState icon="checkCircle" title="No projects are waiting" />}
          {pending.data && pending.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Project</th>
                    <th scope="col">Organisation</th>
                    <th scope="col">Requested</th>
                    <th scope="col">Proposed decision</th>
                    <th scope="col" className="num">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {pending.data.map((p) => {
                    const d = p.openDecision;
                    const mine = d?.requestedBy === myId;
                    return (
                      <tr key={p.projectId}>
                        <td>
                          <span className="primary-cell">{p.name}</span>
                          {p.description && <span className="sub">{p.description}</span>}
                        </td>
                        <td>{p.organisationName}</td>
                        <td className="small">
                          {relativeTime(p.createdAt)} · {p.members} members
                        </td>
                        <td>
                          {d ? (
                            <>
                              <Badge tone={d.decision === 'ACTIVATE' ? 'success' : 'danger'}>
                                {d.decision === 'ACTIVATE' ? `Activate · ${d.planName}` : 'Decline'}
                              </Badge>
                              <span className="sub">by {d.requestedByName}{mine ? ' (you)' : ''}</span>
                            </>
                          ) : (
                            <span className="muted small">None yet</span>
                          )}
                        </td>
                        <td className="num">
                          {!d && (
                            <button type="button" className="btn-primary btn-sm" onClick={() => setDeciding(p)}>
                              Decide
                            </button>
                          )}
                          {d && mine && (
                            <div className="row" style={{ justifyContent: 'flex-end' }}>
                              <span className="small">Needs a second approver</span>
                              <button type="button" className="btn-ghost btn-sm" onClick={() => void act(`/api/v1/staff/project-decisions/${d.id}/cancel`, 'Cancelled.', ['project-requests'])}>
                                Cancel
                              </button>
                            </div>
                          )}
                          {d && !mine && (
                            <div className="row" style={{ justifyContent: 'flex-end' }}>
                              <button type="button" className="btn-secondary btn-sm" onClick={() => void act(`/api/v1/staff/project-decisions/${d.id}/reject`, 'Rejected.', ['project-requests'])}>
                                Reject
                              </button>
                              <button
                                type="button"
                                className="btn-primary btn-sm"
                                onClick={() => {
                                  if (window.confirm(d.decision === 'ACTIVATE' ? `Activate ${p.name} on ${d.planName}? Billing starts now.` : `Decline ${p.name}?`)) {
                                    void act(`/api/v1/staff/project-decisions/${d.id}/approve`, d.decision === 'ACTIVATE' ? 'Project activated.' : 'Project declined.', ['project-requests', 'staff-dashboard']);
                                  }
                                }}
                              >
                                <Icon name="check" size={15} />
                                Approve
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      {tab === 'plans' && (
        <Card title="Billing plans" flush>
          {plans.isPending && <div className="card-body"><Loading /></div>}
          {plans.data && plans.data.length === 0 && <EmptyState icon="layers" title="No plans yet" action={<button type="button" className="btn-primary" onClick={() => setProposing(true)}>Propose the first plan</button>} />}
          {plans.data && plans.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Plan</th>
                    <th scope="col" className="num">Monthly fee</th>
                    <th scope="col" className="num">Included BRDs</th>
                    <th scope="col" className="num">Small · Medium · Large</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="num">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {plans.data.map((p) => {
                    const mine = p.proposedBy === myId;
                    return (
                      <tr key={p.id}>
                        <td>
                          <span className="primary-cell">{p.name}</span>
                          <span className="sub">
                            Proposed by {p.proposedByName}
                            {p.approvedByName ? ` · approved by ${p.approvedByName}` : ''}
                          </span>
                        </td>
                        <td className="num">{money(p.currency, p.monthlyFee)}</td>
                        <td className="num">{p.includedBrds}</td>
                        <td className="num small">
                          {money(p.currency, p.priceSmall)} · {money(p.currency, p.priceMedium)} · {money(p.currency, p.priceLarge)}
                        </td>
                        <td>
                          <Badge tone={PLAN_TONE[p.status] ?? 'neutral'}>{p.status.charAt(0) + p.status.slice(1).toLowerCase()}</Badge>
                        </td>
                        <td className="num">
                          {p.status === 'PROPOSED' && mine && <span className="small">Needs a second approver</span>}
                          {p.status === 'PROPOSED' && !mine && (
                            <div className="row" style={{ justifyContent: 'flex-end' }}>
                              <button type="button" className="btn-secondary btn-sm" onClick={() => void act(`/api/v1/staff/billing/plans/${p.id}/reject`, 'Plan rejected.', ['billing-plans'])}>
                                Reject
                              </button>
                              <button type="button" className="btn-primary btn-sm" onClick={() => void act(`/api/v1/staff/billing/plans/${p.id}/approve`, 'Plan approved.', ['billing-plans'])}>
                                Approve
                              </button>
                            </div>
                          )}
                          {p.status === 'ACTIVE' && (
                            <button
                              type="button"
                              className="btn-ghost btn-sm"
                              onClick={() => {
                                if (window.confirm(`Retire ${p.name}? Projects already on it keep it; it cannot be chosen for new projects.`)) {
                                  void act(`/api/v1/staff/billing/plans/${p.id}/retire`, 'Plan retired.', ['billing-plans']);
                                }
                              }}
                            >
                              Retire
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      {tab === 'report' && (
        <Card
          title="Monthly report"
          flush
          actions={
            <>
              <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} aria-label="Month" style={{ width: 170 }} />
              <a className="btn btn-secondary btn-sm" href={`/api/v1/staff/billing/report.csv?month=${month}`}>
                <Icon name="file" size={15} />
                Download CSV
              </a>
            </>
          }
        >
          {report.isPending && <div className="card-body"><Loading /></div>}
          {report.isError && <div className="card-body"><ErrorText>The report could not be loaded.</ErrorText></div>}
          {report.data && report.data.lines.length === 0 && report.data.brds.length === 0 && (
            <EmptyState icon="chart" title="Nothing billed in this month" />
          )}
          {report.data && report.data.lines.length > 0 && (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Project fees</caption>
                <thead>
                  <tr>
                    <th scope="col">Organisation</th>
                    <th scope="col">Project</th>
                    <th scope="col">Plan</th>
                    <th scope="col">Billed period</th>
                    <th scope="col" className="num">Active days</th>
                    <th scope="col" className="num">Project fee</th>
                  </tr>
                </thead>
                <tbody>
                  {report.data.lines.map((l) => (
                    <tr key={`${l.organisationName}-${l.projectName}`}>
                      <td>{l.organisationName}</td>
                      <td className="primary-cell">{l.projectName}</td>
                      <td>{l.planName}</td>
                      <td className="small">
                        {formatDate(l.billedFrom)} → {formatDate(l.billedTo)}
                      </td>
                      <td className="num">
                        {l.activeDays} / {l.daysInMonth}
                      </td>
                      <td className="num">{money(l.currency, l.projectFee)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {report.data && report.data.brds.length > 0 && (
            <>
              <div className="card-body" style={{ paddingBottom: 0 }}>
                <strong>BRD charges</strong>
                <span className="small"> · the monthly allowance applies first, then the size price</span>
              </div>
              <div className="table-wrap">
                <table>
                  <caption className="sr-only">BRD charges</caption>
                  <thead>
                    <tr>
                      <th scope="col">Organisation</th>
                      <th scope="col">Project</th>
                      <th scope="col">Requirement</th>
                      <th scope="col">Size</th>
                      <th scope="col">Accepted</th>
                      <th scope="col" className="num">Charge</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.data.brds.map((b) => (
                      <tr key={b.requirementId}>
                        <td>{b.organisationName}</td>
                        <td>{b.projectName}</td>
                        <td className="mono">{b.requirementKey}</td>
                        <td>
                          {SIZE_LABEL[b.size] ?? b.size} <span className="small">(estimated)</span>
                        </td>
                        <td className="small">{formatDate(b.acceptedOn)}</td>
                        <td className="num">
                          {b.included ? (
                            <Badge tone="success">Included</Badge>
                          ) : (
                            money(b.currency, b.charge)
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
          {report.data && (report.data.lines.length > 0 || report.data.brds.length > 0) && (
            <div className="card-footer">
              {report.data.totals.map((t) => (
                <strong key={t.currency}>Total {money(t.currency, t.amount)}</strong>
              ))}
            </div>
          )}
        </Card>
      )}
      {tab === 'ai' && (
        <>
          <div className="row" style={{ marginBottom: '0.75rem' }}>
            <label className="row small">
              Month
              <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} style={{ width: 170 }} />
            </label>
          </div>
          <AiCostReport month={month} />
        </>
      )}
      {proposing && <PlanModal onClose={() => setProposing(false)} />}
      {deciding && <DecisionModal project={deciding} plans={plans.data ?? []} onClose={() => setDeciding(null)} />}
    </>
  );
}
