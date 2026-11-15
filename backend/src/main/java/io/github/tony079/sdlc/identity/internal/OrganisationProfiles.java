package io.github.tony079.sdlc.identity.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.common.api.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * The organisation record kept by Garuda (GD-17): validation, JSON for requests, and storage. Writes succeed only as
 * the execution of an approved onboarding or profile request in the same transaction (tenant_profile_guard).
 */
@Component
class OrganisationProfiles {

    static final Pattern DOMAIN = Pattern.compile("^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    /** The editable record. Mandatory for the pilot: legal name, display name and at least one allowed domain. */
    record Profile(String legalName, String displayName, String industry, String country, String address, String website,
                   String billingContactName, String billingContactEmail, String technicalContactName,
                   String technicalContactEmail, String supportEmail, String contractRef, LocalDate contractStart,
                   LocalDate renewalDate, Integer userLimit, List<String> allowedEmailDomains, String dataRegion,
                   String mfaPolicy) {
    }

    record StoredProfile(Profile profile, OffsetDateTime updatedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OrganisationProfiles(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Trims and validates; throws a 422 naming the first problem. The admin email, if given, must be in a domain. */
    Profile validate(Profile p, String adminEmail) {
        if (p == null) {
            throw ApiException.validation("Enter the organisation record.");
        }
        String legal = text(p.legalName(), 300);
        String display = text(p.displayName(), 200);
        if (legal == null || display == null) {
            throw ApiException.validation("Enter the legal name and the display name.");
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String d : p.allowedEmailDomains() == null ? List.<String>of() : p.allowedEmailDomains()) {
            String domain = d == null ? "" : d.strip().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
            if (domain.isEmpty()) {
                continue;
            }
            if (!DOMAIN.matcher(domain).matches()) {
                throw ApiException.validation("“" + domain + "” is not a valid email domain.");
            }
            domains.add(domain);
        }
        if (domains.isEmpty() || domains.size() > 20) {
            throw ApiException.validation("Enter 1 to 20 allowed email domains, for example gajalatech.com.");
        }
        if (adminEmail != null && !domains.contains(adminEmail.substring(adminEmail.indexOf('@') + 1).toLowerCase(Locale.ROOT))) {
            throw ApiException.validation("The Organisation Admin's email must be in one of the allowed domains.");
        }
        for (String email : new String[] {p.billingContactEmail(), p.technicalContactEmail(), p.supportEmail()}) {
            if (email != null && !email.isBlank() && !OrganisationUserService.EMAIL.matcher(email.strip().toLowerCase(Locale.ROOT)).matches()) {
                throw ApiException.validation("“" + email + "” is not a valid email address.");
            }
        }
        if (p.userLimit() != null && p.userLimit() <= 0) {
            throw ApiException.validation("The user limit must be a positive number.");
        }
        if (p.contractStart() != null && p.renewalDate() != null && p.renewalDate().isBefore(p.contractStart())) {
            throw ApiException.validation("The renewal date must be after the contract start.");
        }
        String mfa = p.mfaPolicy() == null || p.mfaPolicy().isBlank() ? "ADMINS" : p.mfaPolicy();
        if (!mfa.equals("ADMINS") && !mfa.equals("ALL_USERS")) {
            throw ApiException.validation("Choose an MFA policy.");
        }
        return new Profile(legal, display, text(p.industry(), 100), text(p.country(), 100), text(p.address(), 1000),
                text(p.website(), 300), text(p.billingContactName(), 200), lower(p.billingContactEmail()),
                text(p.technicalContactName(), 200), lower(p.technicalContactEmail()), lower(p.supportEmail()),
                text(p.contractRef(), 100), p.contractStart(), p.renewalDate(), p.userLimit(), new ArrayList<>(domains),
                text(p.dataRegion(), 50), mfa);
    }

    String toJson(Profile p) {
        return json.writeValueAsString(p);
    }

    Profile fromJson(String value) {
        return value == null ? null : json.readValue(value, Profile.class);
    }

    /** Inserts or replaces the record. Must run in the transaction that executes the approved request. */
    void save(UUID tenantId, Profile p) {
        jdbc.sql("""
                INSERT INTO app.tenant_profile (profile_tenant_id, legal_name, display_name, industry, country, address, website,
                       billing_contact_name, billing_contact_email, technical_contact_name, technical_contact_email,
                       support_email, contract_ref, contract_start, renewal_date, user_limit, allowed_email_domains,
                       data_region, mfa_policy)
                VALUES (:tenant, :legal, :display, :industry, :country, :address, :website, :billingName, :billingEmail,
                        :techName, :techEmail, :support, :contract, :start, :renewal, :limit, CAST(:domains AS text[]),
                        :region, :mfa)
                ON CONFLICT (profile_tenant_id) DO UPDATE SET legal_name = EXCLUDED.legal_name,
                       display_name = EXCLUDED.display_name, industry = EXCLUDED.industry, country = EXCLUDED.country,
                       address = EXCLUDED.address, website = EXCLUDED.website,
                       billing_contact_name = EXCLUDED.billing_contact_name,
                       billing_contact_email = EXCLUDED.billing_contact_email,
                       technical_contact_name = EXCLUDED.technical_contact_name,
                       technical_contact_email = EXCLUDED.technical_contact_email, support_email = EXCLUDED.support_email,
                       contract_ref = EXCLUDED.contract_ref, contract_start = EXCLUDED.contract_start,
                       renewal_date = EXCLUDED.renewal_date, user_limit = EXCLUDED.user_limit,
                       allowed_email_domains = EXCLUDED.allowed_email_domains, data_region = EXCLUDED.data_region,
                       mfa_policy = EXCLUDED.mfa_policy
                """).param("tenant", tenantId).param("legal", p.legalName()).param("display", p.displayName())
                .param("industry", p.industry()).param("country", p.country()).param("address", p.address())
                .param("website", p.website()).param("billingName", p.billingContactName())
                .param("billingEmail", p.billingContactEmail()).param("techName", p.technicalContactName())
                .param("techEmail", p.technicalContactEmail()).param("support", p.supportEmail())
                .param("contract", p.contractRef()).param("start", p.contractStart()).param("renewal", p.renewalDate())
                .param("limit", p.userLimit())
                .param("domains", "{" + String.join(",", p.allowedEmailDomains()) + "}")
                .param("region", p.dataRegion()).param("mfa", p.mfaPolicy()).update();
    }

    /** The record of a tenant, if one exists. Runs in the caller's transaction (RLS: the tenant or staff context). */
    Optional<StoredProfile> find(UUID tenantId) {
        return jdbc.sql("""
                SELECT legal_name, display_name, industry, country, address, website, billing_contact_name,
                       billing_contact_email, technical_contact_name, technical_contact_email, support_email,
                       contract_ref, contract_start, renewal_date, user_limit, allowed_email_domains, data_region,
                       mfa_policy, updated_at
                FROM app.tenant_profile WHERE profile_tenant_id = :tenant
                """).param("tenant", tenantId).query((rs, n) -> {
                    java.sql.Array domains = rs.getArray(16);
                    return new StoredProfile(new Profile(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                            rs.getObject(13, LocalDate.class), rs.getObject(14, LocalDate.class),
                            (Integer) rs.getObject(15), List.of((String[]) domains.getArray()), rs.getString(17),
                            rs.getString(18)), rs.getObject(19, OffsetDateTime.class));
                }).optional();
    }

    /** The MFA policy of a tenant: ALL_USERS requires TOTP of every user; otherwise Organisation Admins only. */
    boolean mfaForAllUsers(UUID tenantId) {
        return jdbc.sql("SELECT mfa_policy = 'ALL_USERS' FROM app.tenant_profile WHERE profile_tenant_id = :tenant")
                .param("tenant", tenantId).query(Boolean.class).optional().orElse(false);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw ApiException.validation("A value is longer than " + max + " characters.");
        }
        return trimmed;
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
