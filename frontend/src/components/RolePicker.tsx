import type { ProjectRole } from '../api/types';
import { ROLE_LABELS } from '../ui/components';

export const PROJECT_ROLES: ProjectRole[] = ['REQUIREMENT_AUTHOR', 'BRD_APPROVER', 'TECHNICAL_APPROVER', 'ESTIMATOR', 'VIEWER'];

export const ROLE_HELP: Record<ProjectRole, string> = {
  REQUIREMENT_AUTHOR: 'Creates requirements, uploads documents, answers clarifications and reviews the BRD of their own requirements.',
  BRD_APPROVER: 'Approves BRDs, decides change requests and repository links, and sees restricted requirements.',
  TECHNICAL_APPROVER: 'Owns the technical design: starts it after BRD approval, reviews it and approves it.',
  ESTIMATOR: 'Owns the estimate: starts it after design approval, reviews and adjusts it; a BRD Approver approves it. Only Estimators and BRD Approvers see estimates.',
  VIEWER: 'Reads requirements, approved BRDs and designs of the project; cannot change anything. Held on its own.',
};

/** GD-27: Viewer is held alone; choosing it clears the others, and choosing another role clears Viewer. */
export function toggleRole(current: ProjectRole[], role: ProjectRole): ProjectRole[] {
  if (current.includes(role)) {
    return current.filter((r) => r !== role);
  }
  if (role === 'VIEWER') {
    return ['VIEWER'];
  }
  return PROJECT_ROLES.filter((r) => r !== 'VIEWER' && (r === role || current.includes(r)));
}

/** Checkbox list of project roles; {@code compact} hides the explanations (used in table rows). */
export function RolePicker({ value, onChange, compact = false, name = 'roles' }: {
  value: ProjectRole[];
  onChange: (roles: ProjectRole[]) => void;
  compact?: boolean;
  name?: string;
}) {
  return (
    <fieldset className="field" style={compact ? { display: 'flex', flexWrap: 'wrap', gap: '0.25rem 0.9rem', margin: 0 } : undefined}>
      <legend className={compact ? 'sr-only' : undefined}>Project roles</legend>
      {PROJECT_ROLES.map((r) => (
        <label key={r} className="check" style={{ alignItems: 'flex-start' }}>
          <input type="checkbox" name={name} checked={value.includes(r)} onChange={() => onChange(toggleRole(value, r))} />
          <span>
            <strong>{ROLE_LABELS[r]}</strong>
            {!compact && <span className="hint" style={{ display: 'block' }}>{ROLE_HELP[r]}</span>}
          </span>
        </label>
      ))}
    </fieldset>
  );
}
