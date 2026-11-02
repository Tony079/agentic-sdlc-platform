import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Project, ProjectList } from '../api/types';

function createErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'PROJECT_NAME_TAKEN') return 'A project with this name already exists.';
    if (error.code === 'VALIDATION_FAILED') return 'The project name must be 1 to 200 characters.';
    if (error.status === 403) return 'You are not allowed to create projects.';
  }
  return 'The project could not be created.';
}

export function ProjectsPage() {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');

  const projects = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.get<ProjectList>('/api/v1/projects'),
  });

  const create = useMutation({
    mutationFn: (projectName: string) => api.post<Project>('/api/v1/projects', { name: projectName }),
    onSuccess: () => {
      setName('');
      return queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    create.mutate(name.trim());
  }

  return (
    <section>
      <h1>Projects</h1>
      <form onSubmit={handleSubmit} className="inline-form">
        <label>
          New project name
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} />
        </label>
        <button type="submit" disabled={create.isPending || name.trim().length === 0}>
          Create project
        </button>
      </form>
      {create.isError && (
        <p role="alert" className="error">
          {createErrorMessage(create.error)}
        </p>
      )}

      {projects.isPending && <p>Loading projects…</p>}
      {projects.isError && (
        <p role="alert" className="error">
          Projects could not be loaded.
        </p>
      )}
      {projects.data && projects.data.items.length === 0 && <p>No projects yet.</p>}
      {projects.data && projects.data.items.length > 0 && (
        <table>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Created</th>
            </tr>
          </thead>
          <tbody>
            {projects.data.items.map((project) => (
              <tr key={project.id}>
                <td>{project.name}</td>
                <td>{new Date(project.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
