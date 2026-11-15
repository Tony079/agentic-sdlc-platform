import type { OrganisationProfile } from '../api/types';
import { Badge } from '../ui/components';

export const EMPTY_PROFILE: OrganisationProfile = {
  legalName: '',
  displayName: '',
  industry: '',
  country: '',
  address: '',
  website: '',
  billingContactName: '',
  billingContactEmail: '',
  technicalContactName: '',
  technicalContactEmail: '',
  supportEmail: '',
  contractRef: '',
  contractStart: '',
  renewalDate: '',
  userLimit: null,
  allowedEmailDomains: [],
  dataRegion: '',
  mfaPolicy: 'ADMINS',
};

/** Empty strings become null so optional fields are not stored as blanks. */
export function cleanProfile(p: OrganisationProfile): OrganisationProfile {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(p)) {
    out[k] = typeof v === 'string' && v.trim() === '' ? null : v;
  }
  return out as unknown as OrganisationProfile;
}

/** The organisation record kept by Garuda (GD-17). Mandatory: legal name, display name, allowed email domains. */
export function OrganisationProfileForm({
  value,
  onChange,
}: {
  value: OrganisationProfile;
  onChange: (next: OrganisationProfile) => void;
}) {
  const set = <K extends keyof OrganisationProfile>(key: K, v: OrganisationProfile[K]) => onChange({ ...value, [key]: v });
  const text = (key: keyof OrganisationProfile, label: string, opts: { type?: string; max?: number; span?: boolean } = {}) => (
    <label className={opts.span ? 'span-2' : undefined}>
      {label}
      <input
        type={opts.type ?? 'text'}
        value={(value[key] as string | null | undefined) ?? ''}
        maxLength={opts.max ?? 200}
        onChange={(e) => set(key, e.target.value as never)}
      />
    </label>
  );
  return (
    <>
      <h3>Identity</h3>
      <div className="form-grid">
        {text('legalName', 'Legal name *', { max: 300 })}
        {text('displayName', 'Display name *')}
        {text('industry', 'Industry', { max: 100 })}
        {text('country', 'Country', { max: 100 })}
        {text('website', 'Website', { max: 300 })}
        {text('address', 'Registered address', { max: 1000 })}
      </div>
      <h3>Contacts</h3>
      <div className="form-grid">
        {text('billingContactName', 'Billing contact')}
        {text('billingContactEmail', 'Billing email', { type: 'email', max: 320 })}
        {text('technicalContactName', 'Technical contact')}
        {text('technicalContactEmail', 'Technical email', { type: 'email', max: 320 })}
        {text('supportEmail', 'Support email', { type: 'email', max: 320 })}
      </div>
      <h3>Commercial</h3>
      <div className="form-grid">
        {text('contractRef', 'Contract reference', { max: 100 })}
        <label>
          User limit
          <input
            type="number"
            min={1}
            value={value.userLimit ?? ''}
            onChange={(e) => set('userLimit', e.target.value ? Number(e.target.value) : null)}
          />
        </label>
        {text('contractStart', 'Contract start', { type: 'date' })}
        {text('renewalDate', 'Renewal date', { type: 'date' })}
      </div>
      <h3>Security</h3>
      <div className="form-grid">
        <label className="span-2">
          Allowed email domains *
          <input
            value={value.allowedEmailDomains.join(', ')}
            placeholder="gajalatech.com, gajalatech.in"
            onChange={(e) =>
              set(
                'allowedEmailDomains',
                e.target.value
                  .split(/[,\s]+/)
                  .map((d) => d.trim().toLowerCase().replace(/^@/, ''))
                  .filter((d, i, all) => d || i === all.length - 1),
              )
            }
          />
          <span className="hint">
            Only people with these email domains can be invited, by Garuda or by the organisation. Separate with commas.
          </span>
          {value.allowedEmailDomains.filter(Boolean).length > 0 && (
            <span className="row" style={{ gap: '0.3rem' }}>
              {value.allowedEmailDomains.filter(Boolean).map((d) => (
                <Badge key={d} tone="neutral" dot={false}>
                  @{d}
                </Badge>
              ))}
            </span>
          )}
        </label>
        {text('dataRegion', 'Data region', { max: 50 })}
        <label>
          Two-step verification
          <select value={value.mfaPolicy} onChange={(e) => set('mfaPolicy', e.target.value as OrganisationProfile['mfaPolicy'])}>
            <option value="ADMINS">Organisation Admins only</option>
            <option value="ALL_USERS">Every user</option>
          </select>
        </label>
      </div>
    </>
  );
}

/** Read-only view of the record. */
export function OrganisationProfileView({ profile }: { profile: OrganisationProfile }) {
  const row = (label: string, v: string | number | null | undefined) => (
    <>
      <dt>{label}</dt>
      <dd>{v === null || v === undefined || v === '' ? <span className="muted">—</span> : v}</dd>
    </>
  );
  return (
    <dl className="details">
      {row('Legal name', profile.legalName)}
      {row('Display name', profile.displayName)}
      {row('Industry', profile.industry)}
      {row('Country', profile.country)}
      {row('Address', profile.address)}
      {row('Website', profile.website)}
      {row('Billing contact', [profile.billingContactName, profile.billingContactEmail].filter(Boolean).join(' · '))}
      {row('Technical contact', [profile.technicalContactName, profile.technicalContactEmail].filter(Boolean).join(' · '))}
      {row('Support email', profile.supportEmail)}
      {row('Contract', profile.contractRef)}
      {row('Contract period', [profile.contractStart, profile.renewalDate].filter(Boolean).join(' → '))}
      {row('User limit', profile.userLimit)}
      <dt>Allowed email domains</dt>
      <dd>
        <span className="row" style={{ gap: '0.3rem' }}>
          {profile.allowedEmailDomains.map((d) => (
            <Badge key={d} tone="neutral" dot={false}>
              @{d}
            </Badge>
          ))}
        </span>
      </dd>
      {row('Data region', profile.dataRegion)}
      {row('Two-step verification', profile.mfaPolicy === 'ALL_USERS' ? 'Every user' : 'Organisation Admins only')}
    </dl>
  );
}
