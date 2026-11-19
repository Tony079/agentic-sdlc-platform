import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { FieldError, Project, RequirementContent, RequirementKind } from '../api/types';
import type { EnvironmentOverview } from './EnvironmentsTab';

/** Field limits from approved decision Q1 (the server enforces them; the UI only helps). */
export const LIMITS = {
  title: 200,
  description: 20_000,
  businessObjective: 5_000,
  expectedOutcomes: 10_000,
  acceptanceCriteria: 10_000,
  repositoryUrl: 300,
  constraints: 10_000,
  integrations: 10_000,
} as const;

type TextField = keyof typeof LIMITS;

const LABELS: Record<TextField, string> = {
  title: 'Title',
  description: 'Description',
  businessObjective: 'Business objective',
  expectedOutcomes: 'Expected outcomes',
  acceptanceCriteria: 'Acceptance criteria (optional)',
  repositoryUrl: 'Repository URL',
  constraints: 'Constraints',
  integrations: 'Integrations',
};

const MULTILINE: TextField[] = [
  'description',
  'businessObjective',
  'expectedOutcomes',
  'acceptanceCriteria',
  'constraints',
  'integrations',
];

export function fieldMessage(field: string, code: string): string {
  switch (code) {
    case 'REQUIRED':
      return 'This field is required to submit.';
    case 'TOO_LONG':
      return `Too long (at most ${LIMITS[field as TextField] ?? ''} characters).`;
    case 'INVALID_FORMAT':
      return field === 'repositoryUrl'
        ? 'Use the form https://github.com/owner/repository.'
        : 'The format is not valid.';
    case 'REQUIRED_ENVIRONMENT':
      return 'Choose the environment to analyse.';
    case 'MUST_BE_ABSENT':
      return 'Not allowed for a new product.';
    case 'INVALID_VALUE':
      return 'This value is not valid.';
    default:
      return 'This value is not valid.';
  }
}

export interface RequirementFormValues extends RequirementContent {
  projectId: string;
  /** GD-23: the project environment an existing product is analysed against (PROD by default). */
  targetEnvironmentId?: string | null;
}

interface Props {
  initial: RequirementFormValues;
  projects: Project[];
  /** Project cannot change after creation. */
  projectLocked: boolean;
  /** The requirement type cannot change after submission (decision Q-M3-5). */
  kindLocked?: boolean;
  fieldErrors: FieldError[];
  busy: boolean;
  onSave: (values: RequirementFormValues, submitAfterSave: boolean) => void;
}

/** The requirement form (PRD FR-REQ-01). Drafts may be partial; completeness is checked by the server at submission. */
export function RequirementForm({ initial, projects, projectLocked, kindLocked = false, fieldErrors, busy, onSave }: Props) {
  const [values, setValues] = useState<RequirementFormValues>(initial);

  const errorFor = (field: string) => fieldErrors.find((e) => e.field === field);

  function set<K extends keyof RequirementFormValues>(field: K, value: RequirementFormValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  function setKind(kind: RequirementKind) {
    // A new product never carries a repository URL or an environment.
    setValues((current) => ({
      ...current,
      kind,
      repositoryUrl: kind === 'NEW_PRODUCT' ? null : current.repositoryUrl,
      targetEnvironmentId: kind === 'NEW_PRODUCT' ? null : current.targetEnvironmentId,
    }));
  }

  function handle(submitAfterSave: boolean) {
    return (event: FormEvent) => {
      event.preventDefault();
      onSave({ ...values, targetEnvironmentId: environmentIdRef.current }, submitAfterSave);
    };
  }
  const environmentIdRef = { current: values.targetEnvironmentId ?? null };

  function textField(field: TextField) {
    const error = errorFor(field);
    const id = `field-${field}`;
    const common = {
      id,
      value: values[field] ?? '',
      maxLength: LIMITS[field],
      'aria-invalid': error ? true : undefined,
      'aria-describedby': error ? `${id}-error` : undefined,
      onChange: (e: { target: { value: string } }) => set(field, e.target.value),
    };
    return (
      <div className="field" key={field}>
        <label htmlFor={id}>{LABELS[field]}</label>
        {MULTILINE.includes(field) ? <textarea rows={4} {...common} /> : <input {...common} />}
        {error && (
          <p id={`${id}-error`} className="error">
            {fieldMessage(field, error.code)}
          </p>
        )}
      </div>
    );
  }

  const projectError = errorFor('projectId');
  const environmentError = errorFor('targetEnvironmentId');
  const existingProduct = values.kind === 'EXISTING_REPOSITORY';
  // Earlier requirements were linked by repository URL; they keep it (read-only) unless an environment is chosen.
  const legacyUrl = Boolean(initial.repositoryUrl) && !initial.targetEnvironmentId;
  const environments = useQuery({
    queryKey: ['environments', values.projectId],
    queryFn: () => api.get<EnvironmentOverview>(`/api/v1/projects/${values.projectId}/environments`),
    enabled: Boolean(values.projectId) && existingProduct,
  });
  const envList = [...(environments.data?.environments ?? [])].sort((a, b) => Number(b.production) - Number(a.production));
  const prodId = envList.find((e) => e.production)?.id;
  // PROD is the default (GD-23) while nothing has been chosen.
  const environmentId = values.targetEnvironmentId ?? (existingProduct && !legacyUrl ? prodId ?? null : null);
  environmentIdRef.current = environmentId;
  const chosen = envList.find((e) => e.id === environmentId);

  return (
    <form className="requirement-form" onSubmit={handle(false)} noValidate>
      <div className="field">
        <label htmlFor="field-projectId">Project</label>
        <select
          id="field-projectId"
          value={values.projectId}
          disabled={projectLocked}
          onChange={(e) => set('projectId', e.target.value)}
        >
          <option value="">Select a project</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
        {projectError && <p className="error">{fieldMessage('projectId', projectError.code)}</p>}
      </div>

      <fieldset className="field">
        <legend>Requirement type</legend>
        <label className="inline">
          <input
            type="radio"
            name="kind"
            checked={values.kind === 'EXISTING_REPOSITORY'}
            disabled={kindLocked}
            onChange={() => setKind('EXISTING_REPOSITORY')}
          />
          Enhancement to an existing product
        </label>
        <label className="inline">
          <input
            type="radio"
            name="kind"
            checked={values.kind === 'NEW_PRODUCT'}
            disabled={kindLocked}
            onChange={() => setKind('NEW_PRODUCT')}
          />
          New product
        </label>
        {kindLocked && <p className="hint">The requirement type cannot be changed after submission.</p>}
        {values.kind === 'NEW_PRODUCT' && (
          <p className="notice" role="note">
            A new product has no existing code, so no environment is needed. The BA agent writes the BRD from this
            requirement and the documents you upload (describe the product well and attach any briefs, mock-ups or
            examples). After BRD approval, the Technical Approver&apos;s design proposes the technology stack, structure and
            deployment from scratch.
          </p>
        )}
      </fieldset>

      {textField('title')}
      {existingProduct && (
        <div className="field">
          <label htmlFor="field-targetEnvironmentId">Environment to analyse</label>
          {values.projectId && environments.data && envList.length === 0 ? (
            <p className="notice" role="note">
              This project has no environments yet. Its Environment Manager
              {environments.data.environmentManagerName ? ` (${environments.data.environmentManagerName})` : ''} adds them on
              the project&apos;s Environments tab. You can save a draft meanwhile.
            </p>
          ) : (
            <select
              id="field-targetEnvironmentId"
              value={environmentId ?? ''}
              disabled={!values.projectId || environments.isPending}
              aria-invalid={environmentError ? true : undefined}
              onChange={(e) => set('targetEnvironmentId', e.target.value || null)}
            >
              <option value="">{values.projectId ? 'Choose an environment' : 'Choose a project first'}</option>
              {envList.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name}
                  {e.production ? ' (PROD)' : ''}
                </option>
              ))}
            </select>
          )}
          {chosen && (
            <p className="hint">
              {chosen.branches.length === 0
                ? 'This environment has no branches yet.'
                : `Analyses ${chosen.branches.map((b) => `${b.repository} @ ${b.branch}`).join(', ')}.`}
            </p>
          )}
          {environmentError && <p className="error">{fieldMessage('targetEnvironmentId', 'REQUIRED_ENVIRONMENT')}</p>}
        </div>
      )}
      {existingProduct && legacyUrl && (
        <div className="field">
          <label htmlFor="field-repositoryUrl">Repository URL (earlier link)</label>
          <input id="field-repositoryUrl" value={values.repositoryUrl ?? ''} readOnly />
          <p className="hint">Choose an environment above to analyse the project&apos;s environment instead.</p>
        </div>
      )}
      {textField('description')}
      {textField('businessObjective')}
      {textField('expectedOutcomes')}
      {textField('acceptanceCriteria')}
      {textField('constraints')}
      {textField('integrations')}

      <div className="actions">
        <button type="submit" disabled={busy}>
          Save draft
        </button>
        <button type="button" disabled={busy} onClick={handle(true)}>
          Save and submit
        </button>
      </div>
    </form>
  );
}
