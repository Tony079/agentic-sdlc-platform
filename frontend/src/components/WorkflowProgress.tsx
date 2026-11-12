import type { RequirementState } from '../api/types';

/** The main path of the Release 1 workflow, in order. Side states map onto the step they interrupt. */
const STEPS: { label: string; states: RequirementState[] }[] = [
  { label: 'Draft', states: ['DRAFT'] },
  { label: 'Submitted', states: ['SUBMITTED', 'MANUAL_INTAKE'] },
  { label: 'Repository and documents', states: ['REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION'] },
  { label: 'Analysis', states: ['ANALYSING', 'AWAITING_CLARIFICATION', 'BRD_DRAFTED'] },
  { label: 'Internal review', states: ['INTERNAL_REVIEW', 'REVISION_REQUESTED'] },
  { label: 'Approved', states: ['INTERNAL_APPROVED'] },
  { label: 'Shared with you', states: ['SHARED_WITH_CLIENT', 'CLIENT_CHANGES_REQUESTED'] },
];

const STOPPED: Partial<Record<RequirementState, string>> = {
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

/** Where a requirement stands on the workflow path. Display only; the server owns the state. */
export function WorkflowProgress({ state }: { state: RequirementState }) {
  const stopped = STOPPED[state];
  if (stopped) {
    return (
      <p className="workflow-stopped">
        Workflow ended: <strong>{stopped}</strong>
      </p>
    );
  }
  const current = STEPS.findIndex((step) => step.states.includes(state));
  return (
    <ol className="workflow-steps" aria-label="Workflow progress">
      {STEPS.map((step, index) => (
        <li
          key={step.label}
          className={index < current ? 'done' : index === current ? 'current' : 'todo'}
          aria-current={index === current ? 'step' : undefined}
        >
          {step.label}
        </li>
      ))}
    </ol>
  );
}
