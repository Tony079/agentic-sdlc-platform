package io.github.tony079.sdlc.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import io.github.tony079.sdlc.SdlcPlatformApplication;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL 16 database, prepared exactly as in deployment: the bootstrap scripts in db/bootstrap run as
 * the superuser, then the application's own migrate process applies Liquibase as sdlc_migrator and seeds the
 * development tenants. Tests then connect as sdlc_app.
 *
 * <p>Uses an external PostgreSQL 16 when SDLC_TEST_ADMIN_JDBC_URL (a superuser URL), SDLC_TEST_ADMIN_USER and
 * SDLC_TEST_ADMIN_PASSWORD are set; otherwise starts a postgres:16 Testcontainer. One database per test JVM.
 */
public final class TestDatabase {

    /** Password of the seeded test users: random per test run, never a fixed value in the repository. */
    public static final String SEED_PASSWORD = randomSecret();
    private static final Path BOOTSTRAP = Path.of("..", "db", "bootstrap");

    private static TestDatabase instance;

    private final String adminBaseUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String databaseName;
    private final String appPassword = randomSecret();
    private final String migratorPassword = randomSecret();
    private final String workerPassword = randomSecret();
    private final String provisionerPassword = randomSecret();
    private final String retentionPassword = randomSecret();
    /** Staff MFA encryption key for the test run (random, never committed). */
    public static final String MFA_KEY = java.util.Base64.getEncoder().encodeToString(randomBytes(32));

    private TestDatabase(String adminBaseUrl, String adminUser, String adminPassword) {
        this.adminBaseUrl = adminBaseUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.databaseName = "sdlc_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static synchronized TestDatabase get() {
        if (instance == null) {
            String url = System.getenv("SDLC_TEST_ADMIN_JDBC_URL");
            TestDatabase db;
            if (url != null && !url.isBlank()) {
                db = new TestDatabase(url, System.getenv("SDLC_TEST_ADMIN_USER"), System.getenv("SDLC_TEST_ADMIN_PASSWORD"));
            } else {
                @SuppressWarnings("resource")
                PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
                container.start();
                db = new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword());
            }
            db.prepare();
            instance = db;
        }
        return instance;
    }

    public String appUrl() {
        return databaseUrl(databaseName);
    }

    public String appUser() {
        return "sdlc_app";
    }

    public String appPassword() {
        return appPassword;
    }

    public String workerUser() {
        return "sdlc_worker";
    }

    public String workerPassword() {
        return workerPassword;
    }

    /** Opens a raw connection as sdlc_worker (for privilege tests). */
    public Connection workerConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), workerUser(), workerPassword);
    }

    public String provisionerUser() {
        return "sdlc_provisioner";
    }

    public String provisionerPassword() {
        return provisionerPassword;
    }

    public String retentionPassword() {
        return retentionPassword;
    }

    public java.sql.Connection retentionConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), "sdlc_retention", retentionPassword);
    }

    /** Opens a raw connection as sdlc_provisioner (for provisioning and privilege tests). */
    public Connection provisionerConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), provisionerUser(), provisionerPassword);
    }

    /** Opens a raw connection as sdlc_app (for privilege tests). */
    public Connection appConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), appUser(), appPassword);
    }

    /** Superuser access to the test database, for test setup and assertions only (never used by the application). */
    public JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(appUrl(), adminUser, adminPassword));
    }

    /** Raw superuser connection to the test database (test setup only). */
    public Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(appUrl(), adminUser, adminPassword);
    }

    public UUID tenantId(String slug) {
        return admin().queryForObject("SELECT id FROM app.tenant WHERE slug = ?", UUID.class, slug);
    }

    private void prepare() {
        try (Connection admin = DriverManager.getConnection(adminBaseUrl, adminUser, adminPassword);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
            statement.execute(Files.readString(BOOTSTRAP.resolve("00-roles.sql")));
            statement.execute("ALTER ROLE sdlc_migrator PASSWORD '" + migratorPassword + "'");
            statement.execute("ALTER ROLE sdlc_app PASSWORD '" + appPassword + "'");
            statement.execute("ALTER ROLE sdlc_worker PASSWORD '" + workerPassword + "'");
            statement.execute("ALTER ROLE sdlc_provisioner PASSWORD '" + provisionerPassword + "'");
            statement.execute("ALTER ROLE sdlc_retention PASSWORD '" + retentionPassword + "'");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Bootstrap (roles) failed", e);
        }
        try (Connection admin = DriverManager.getConnection(appUrl(), adminUser, adminPassword);
             Statement statement = admin.createStatement()) {
            statement.execute(Files.readString(BOOTSTRAP.resolve("01-database.sql")));
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Bootstrap (database) failed", e);
        }
        // The real migrate process: Liquibase as sdlc_migrator (acting as sdlc_owner) plus the development seed.
        SdlcPlatformApplication.disableLiquibaseAnalytics();
        new SpringApplicationBuilder(SdlcPlatformApplication.class)
                .profiles("migrate")
                .run("--spring.datasource.url=" + appUrl(),
                        "--spring.datasource.password=" + migratorPassword,
                        "--sdlc.dev-seed.enabled=true",
                        "--sdlc.dev-seed.password=" + SEED_PASSWORD)
                .close();
    }

    private String databaseUrl(String database) {
        int slash = adminBaseUrl.lastIndexOf('/');
        int query = adminBaseUrl.indexOf('?', slash);
        String suffix = query < 0 ? "" : adminBaseUrl.substring(query);
        return adminBaseUrl.substring(0, slash + 1) + database + suffix;
    }

    private static String randomSecret() {
        return HexFormat.of().formatHex(randomBytes(24));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
