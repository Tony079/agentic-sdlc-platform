import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../api/client';
import type { FieldError, ProjectList, RequirementDetail } from '../api/types';
import { RequirementForm, type RequirementFormValues } from '../components/RequirementForm';

const EMPTY: RequirementFormValues = {
  projectId: '',
  kind: 'EXISTING_REPOSITORY',
  title: '',
  description: null,
  businessObjective: null,
  expectedOutcomes: null,
  acceptanceCriteria: null,
  repositoryUrl: null,
  constraints: null,
  integrations: null,
};

function contentPayload(values: RequirementFormValues) {
  return {
    kind: values.kind,
    title: values.title,
    description: values.description,
    businessObjective: values.businessObjective,
    expectedOutcomes: values.expectedOutcomes,
    acceptanceCriteria: values.acceptanceCriteria,
    repositoryUrl: values.kind === 'EXISTING_REPOSITORY' ? values.repositoryUrl : null,
    constraints: values.constraints,
    integrations: values.integrations,
  };
}

/** Create (/requirements/new) or edit (/requirements/:id/edit) a requirement. */
export function RequirementFormPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // One key per form: a repeated create (double click, retry) replays instead of duplicating.
  const idempotencyKey = useRef(crypto.randomUUID());
  const [saved, setSaved] = useState<RequirementDetail | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const existing = useQuery({
    queryKey: ['requirement', id],
    queryFn: () => api.get<RequirementDetail>(`/api/v1/requirements/${id}`),
    enabled: Boolean(id),
  });

  if (projects.isPending || (id && existing.isPending)) {
    return <p>Loading…</p>;
  }
  if (projects.isError || (id && existing.isError)) {
    return (
      <p role="alert" className="error">
        The requirement could not be loaded.
      </p>
    );
  }

  const current = saved ?? existing.data ?? null;
  const initial: RequirementFormValues = current ? { projectId: current.projectId, ...current.content } : EMPTY;

  async function onSave(values: RequirementFormValues, submitAfterSave: boolean) {
    setBusy(true);
    setFieldErrors([]);
    setMessage(null);
    // Tracked locally: React state set during this call is not visible to this closure.
    let draftSaved = false;
    try {
      let detail: RequirementDetail;
      if (current) {
        detail = await api.put<RequirementDetail>(
          `/api/v1/requirements/${current.id}`,
          contentPayload(values),
          ifMatch(current.version),
        );
      } else {
        detail = await api.post<RequirementDetail>(
          '/api/v1/requirements',
          { projectId: values.projectId || null, ...contentPayload(values) },
          { 'Idempotency-Key': idempotencyKey.current },
        );
      }
      setSaved(detail);
      draftSaved = true;
      if (submitAfterSave) {
        detail = await api.post<RequirementDetail>(
          `/api/v1/requirements/${detail.id}/submit`,
          undefined,
          ifMatch(detail.version),
        );
      }
      queryClient.setQueryData(['requirement', detail.id], detail);
      await queryClient.invalidateQueries({ queryKey: ['requirements'] });
      navigate(`/requirements/${detail.id}`);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') {
        setFieldErrors(error.details);
        setMessage(
          draftSaved
            ? 'The draft was saved, but it cannot be submitted yet. Complete the highlighted fields.'
            : 'Please correct the highlighted fields.',
        );
      } else if (error instanceof ApiError && error.code === 'VERSION_CHANGED') {
        setMessage('This requirement was changed elsewhere. Reload the page to see the latest version.');
      } else if (error instanceof ApiError && error.code === 'REQUIREMENT_NOT_EDITABLE') {
        setMessage('This requirement can no longer be edited.');
      } else if (error instanceof ApiError && error.code === 'KIND_CHANGE_NOT_ALLOWED') {
        setMessage('The requirement type cannot be changed after submission.');
      } else {
        setMessage('The requirement could not be saved.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h1>{current ? `Edit ${current.key}` : 'New requirement'}</h1>
      {message && (
        <p role="alert" className="error">
          {message}
        </p>
      )}
      <RequirementForm
        key={current?.id ?? 'new'}
        initial={initial}
        projects={projects.data?.items ?? []}
        projectLocked={Boolean(current)}
        kindLocked={current?.state === 'SUBMITTED'}
        fieldErrors={fieldErrors}
        busy={busy}
        onSave={onSave}
      />
      <p>
        <Link to={current ? `/requirements/${current.id}` : '/requirements'}>Back</Link>
      </p>
    </section>
  );
}
