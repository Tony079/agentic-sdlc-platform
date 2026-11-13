import { Link } from 'react-router-dom';
import { PageHeader } from '../ui/components';

export function NotFoundPage() {
  return (
    <section>
      <PageHeader title="Page not found" />
      <p>
        <Link to="/projects">Go to projects</Link>
      </p>
    </section>
  );
}
