import { useState, type FormEvent } from 'react';
import type { FieldError, Project, RequirementContent, RequirementKind } from '../api/types';

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
}

interface Props {
  initial: RequirementFormValues;
  projects: Project[];
  /** Project cannot change after creation. */
  projectLocked: boolean;
  fieldErrors: FieldError[];
  busy: boolean;
  onSave: (values: RequirementFormValues, submitAfterSave: boolean) => void;
}

/** The requirement form (PRD FR-REQ-01). Drafts may be partial; completeness is checked by the server at submission. */
export function RequirementForm({ initial, projects, projectLocked, fieldErrors, busy, onSave }: Props) {
  const [values, setValues] = useState<RequirementFormValues>(initial);

  const errorFor = (field: string) => fieldErrors.find((e) => e.field === field);

  function set<K extends keyof RequirementFormValues>(field: K, value: RequirementFormValues[K]) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  function setKind(kind: RequirementKind) {
    // A new product never carries a repository URL.
    setValues((current) => ({ ...current, kind, repositoryUrl: kind === 'NEW_PRODUCT' ? null : current.repositoryUrl }));
  }

  function handle(submitAfterSave: boolean) {
    return (event: FormEvent) => {
      event.preventDefault();
      onSave(values, submitAfterSave);
    };
  }

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
            onChange={() => setKind('EXISTING_REPOSITORY')}
          />
          Enhancement to an existing repository
        </label>
        <label className="inline">
          <input type="radio" name="kind" checked={values.kind === 'NEW_PRODUCT'} onChange={() => setKind('NEW_PRODUCT')} />
          New product
        </label>
        {values.kind === 'NEW_PRODUCT' && (
          <p className="notice" role="note">
            Automated greenfield analysis and scaffolding are not supported yet. New-product requests are kept for manual
            intake.
          </p>
        )}
      </fieldset>

      {textField('title')}
      {values.kind === 'EXISTING_REPOSITORY' && textField('repositoryUrl')}
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
