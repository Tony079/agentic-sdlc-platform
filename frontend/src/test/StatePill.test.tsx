import { describe, expect, it } from 'vitest';
import { deliveryStatus } from '../ui/components';

describe('deliveryStatus (GD-27, GD-33, GD-34)', () => {
  it('shows the first stage after the BRD that is not approved yet', () => {
    expect(deliveryStatus(null).label).toBe('Waiting for technical design');
    expect(deliveryStatus('IN_REVIEW', null, null).label).toBe('Design in review');
    expect(deliveryStatus('APPROVED', null, null).label).toBe('Waiting for estimate');
    expect(deliveryStatus('APPROVED', 'GENERATING', null).label).toBe('Estimate being written');
    expect(deliveryStatus('APPROVED', 'APPROVED', null).label).toBe('Waiting for delivery plan');
    expect(deliveryStatus('APPROVED', 'APPROVED', 'IN_REVIEW').label).toBe('Delivery plan in review');
    expect(deliveryStatus('APPROVED', 'APPROVED', 'APPROVED').label).toBe('Delivery plan approved');
    expect(deliveryStatus('APPROVED', 'FAILED', 'APPROVED').label).toBe('Estimate failed');
  });

  it('falls back to the last known stage while a later status is unknown', () => {
    expect(deliveryStatus('APPROVED').label).toBe('Design approved');
    expect(deliveryStatus('APPROVED', 'APPROVED').label).toBe('Estimate approved');
  });
});
