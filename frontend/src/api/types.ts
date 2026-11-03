// Shapes of the backend API (/api/v1). The server is the authority for every permission.

export interface Me {
  userId: string;
  displayName: string;
  email: string;
  tenant: { id: string; name: string };
  roles: string[];
}

export interface Project {
  id: string;
  name: string;
  createdAt: string;
}

export interface ProjectList {
  items: Project[];
}

export interface ErrorEnvelope {
  error: { code: string; message: string; correlationId: string | null };
}
