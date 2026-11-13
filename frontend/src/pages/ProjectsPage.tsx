import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { Project, ProjectList } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../ui/Icon';
import { useToast } from '../ui/Toast';
import { Badge, EmptyState, ErrorText, Loading, Modal, PageHeader, RoleBadge, formatDate } from '../ui/components';

function createErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'PROJECT_NAME_TAKEN') return 'A project with this name already exists.';
    if (error.code === 'VALIDATION_FAILED') return 'The project name must be 1 to 200 characters.';
    if (error.status === 403) return 'Only an Organisation Admin can create projects.';
  }
  return 'The project could not be created.';
}

export function ProjectFormModal({
  project,
  onClose,
}: {
  project?: Project;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState(project?.name ?? '');
  const [description, setDescription] = useState(project?.description ?? '');
  const save = useMutation({
    mutationFn: () =>
      project
        ? api.put<Project>(`/api/v1/projects/${project.id}`, { name: name.trim(), description: description.trim() || null })
        : api.post<Project>('/api/v1/projects', { name: name.trim(), description: description.trim() || null }),
    onSuccess: async (saved) => {
      toast.success(project ? 'Project updated.' : `Project “${saved.name}” created.`);
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      await queryClient.invalidateQueries({ queryKey: ['project', saved.id] });
      onClose();
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }
  return (
    <Modal
      title={project ? 'Edit project' : 'New project'}
      description={project ? undefined : 'A project groups the requirements of one product or business area.'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="project-form" disabled={save.isPending || name.trim().length === 0}>
            {save.isPending ? 'Saving…' : project ? 'Save changes' : 'Create project'}
          </button>
        </>
      }
    >
      <form id="project-form" onSubmit={submit}>
        <label>
          Project name
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} required />
        </label>
        <label>
          Description
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} rows={3} />
          <span className="hint">Optional. Shown to everyone in the project.</span>
        </label>
        {save.isError && <ErrorText>{createErrorMessage(save.error)}</ErrorText>}
      </form>
    </Modal>
  );
}

/** Projects the user can see: all of the organisation's for an Organisation Admin, otherwise their memberships. */
export function ProjectsPage() {
  const { isOrgAdmin } = useAuth();
  const [creating, setCreating] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const projects = useQuery({
    queryKey: ['projects', showArchived ? 'all' : 'active'],
    queryFn: () => api.get<ProjectList>(`/api/v1/projects${showArchived ? '?includeArchived=true' : ''}`),
  });
  const items = projects.data?.items ?? [];

  return (
    <>
      <PageHeader
        title="Projects"
        description={
          isOrgAdmin
            ? 'Every project of your organisation. Add people to a project and give them a role there.'
            : 'The projects you are a member of, with your role in each.'
        }
        actions={
          <>
            {isOrgAdmin && (
              <label className="check" style={{ margin: 0 }}>
                <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
                Show archived
              </label>
            )}
            {isOrgAdmin && (
              <button type="button" className="btn-primary" onClick={() => setCreating(true)}>
                <Icon name="plus" size={16} />
                New project
              </button>
            )}
          </>
        }
      />
      {projects.isPending && <Loading label="Loading projects…" />}
      {projects.isError && <ErrorText>Projects could not be loaded.</ErrorText>}
      {projects.data && items.length === 0 && (
        <div className="card">
          <EmptyState
            icon="folder"
            title="No projects yet"
            action={
              isOrgAdmin && (
                <button type="button" className="btn-primary" onClick={() => setCreating(true)}>
                  Create the first project
                </button>
              )
            }
          >
            {isOrgAdmin
              ? 'Projects group requirements by product or business area, and decide who can see and approve them.'
              : 'You are not a member of any project yet. Ask your Organisation Admin to add you.'}
          </EmptyState>
        </div>
      )}
      {items.length > 0 && (
        <div className="grid grid-3">
          {items.map((project) => (
            <Link key={project.id} to={`/projects/${project.id}`} className="card project-card">
              <div className="spread">
                <span className="stat-icon">
                  <Icon name="folder" size={16} />
                </span>
                {project.status === 'ARCHIVED' ? <Badge tone="neutral">Archived</Badge> : <RoleBadge role={project.myRole} />}
              </div>
              <div>
                <h3 style={{ margin: 0 }}>{project.name}</h3>
                {project.description && (
                  <p className="small" style={{ margin: '0.3rem 0 0' }}>
                    {project.description}
                  </p>
                )}
              </div>
              <div className="meta">
                <span className="row" style={{ gap: '0.3rem' }}>
                  <Icon name="users" size={14} />
                  {project.memberCount ?? 0} members
                </span>
                <span>Created {formatDate(project.createdAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
      {creating && <ProjectFormModal onClose={() => setCreating(false)} />}
    </>
  );
}
