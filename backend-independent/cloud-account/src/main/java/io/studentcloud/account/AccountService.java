package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent JDBC identity/session service. All authorization identities come from current rows. */
public final class AccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ACCOUNT_COLUMNS = "a.id,a.account_id,a.username,a.email,a.password_hash,a.role,a.workspace_id,a.enabled,a.session_version";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration sessionLifetime;
    private final PasswordHasher passwords = new PasswordHasher();

    public AccountService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Duration sessionLifetime) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime);
        if (sessionLifetime.compareTo(Duration.ofSeconds(1)) < 0 || sessionLifetime.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("Session lifetime must be between one second and 30 days");
    }

    public boolean installationStatus() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT installed FROM sc_platform_settings WHERE id=1", Boolean.class));
    }

    public String siteName() {
        return jdbc.queryForObject("SELECT site_name FROM sc_platform_settings WHERE id=1", String.class);
    }

    /** A singleton settings row serializes initializers across processes, rather than a Java monitor. */
    public AccountView install(String siteName, String username, String password) {
        String name = requiredText(siteName, 120, "Site name is required");
        String loginName = normalizedUsername(username);
        requireNewPassword(password);
        String encoded = passwords.hash(password);
        return transactions.execute(status -> {
            Boolean installed = jdbc.queryForObject("SELECT installed FROM sc_platform_settings WHERE id=1 FOR UPDATE", Boolean.class);
            if (Boolean.TRUE.equals(installed)) throw fault("41000", 409, "The platform has already been initialized");
            Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM sc_account", Long.class);
            if (existing == null || existing != 0) throw fault("41000", 409, "Account data already exists; initialization is refused");
            String accountId = "SC" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            long now = clock.millis();
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO sc_account(account_id,username,password_hash,role,enabled,session_version,created_at) VALUES(?,?,?,'ADMIN',TRUE,1,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, accountId);
                statement.setString(2, loginName);
                statement.setString(3, encoded);
                statement.setLong(4, now);
                return statement;
            }, key);
            Number id = Objects.requireNonNull(key.getKey(), "Account identity was not generated");
            if (jdbc.update("UPDATE sc_platform_settings SET site_name=?,installed=TRUE,installed_at=? WHERE id=1 AND installed=FALSE", name, now) != 1)
                throw fault("41000", 409, "Initialization state changed");
            return new AccountView(id.longValue(), accountId, loginName, null, Role.ADMIN, null, true);
        });
    }

    public LoginResult login(String identifier, String password) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 254 || password == null || password.length() > 128)
            throw invalidLogin();
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        return transactions.execute(status -> {
            List<AccountRow> matches = jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM sc_account a WHERE LOWER(a.username)=? OR LOWER(a.email)=? OR LOWER(a.account_id)=? FOR UPDATE",
                    this::account, normalized, normalized, normalized);
            if (matches.size() != 1) throw invalidLogin();
            AccountRow row = matches.getFirst();
            if (!active(row) || !passwords.verify(password, row.passwordHash())) throw invalidLogin();
            long now = clock.millis();
            long expires = Instant.ofEpochMilli(now).plus(sessionLifetime).toEpochMilli();
            jdbc.update("DELETE FROM sc_session WHERE account_id=? AND expires_at<=?", row.id(), now);
            List<String> tokens = jdbc.queryForList("SELECT token_hash FROM sc_session WHERE account_id=? ORDER BY created_at,token_hash", String.class, row.id());
            // Bounded per-account sessions. Login is serialized on the account row.
            for (int i = 0; i <= tokens.size() - 32; i++) jdbc.update("DELETE FROM sc_session WHERE token_hash=?", tokens.get(i));
            byte[] entropy = new byte[32];
            RANDOM.nextBytes(entropy);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            jdbc.update("INSERT INTO sc_session(token_hash,account_id,session_version,expires_at,created_at) VALUES(?,?,?,?,?)",
                    digest(token), row.id(), row.sessionVersion(), expires, now);
            return new LoginResult(row.view(), token, Instant.ofEpochMilli(expires));
        });
    }

    public Actor authenticate(String token) {
        if (!validToken(token)) throw unauthorized();
        return authenticateDigest(digest(token));
    }

    /** An in-process capability for long-lived transports; it exposes neither bearer token nor digest. */
    public SessionReference bindSession(String token) {
        if (!validToken(token)) throw unauthorized();
        String hash = digest(token);
        authenticateDigest(hash);
        return new SessionReference(this, hash);
    }

    /** Every call reads the same live session/account/workspace state as ordinary HTTP authentication. */
    public Actor authenticateSession(SessionReference reference) {
        if (reference == null || reference.issuer != this) throw unauthorized();
        return authenticateDigest(reference.tokenHash);
    }

    private Actor authenticateDigest(String tokenHash) {
        List<SessionRow> sessions = jdbc.query("SELECT " + ACCOUNT_COLUMNS + ",s.session_version AS issued_version,s.expires_at FROM sc_session s JOIN sc_account a ON a.id=s.account_id WHERE s.token_hash=?",
                (result, index) -> new SessionRow(account(result, index), result.getLong("issued_version"), result.getLong("expires_at")), tokenHash);
        if (sessions.size() != 1) throw unauthorized();
        SessionRow session = sessions.getFirst();
        if (session.expiresAt() <= clock.millis() || session.issuedVersion() != session.account().sessionVersion() || !active(session.account()))
            throw unauthorized();
        return session.account().actor();
    }

    public static final class SessionReference {
        private final AccountService issuer;
        private final String tokenHash;
        private SessionReference(AccountService issuer, String tokenHash) { this.issuer = issuer; this.tokenHash = tokenHash; }
    }

    public void logout(String token) {
        if (validToken(token)) jdbc.update("DELETE FROM sc_session WHERE token_hash=?", digest(token));
    }

    /** Caller must resolve actorId through authenticate; an arbitrary HTTP actorId is never accepted. */
    public void changePassword(long actorId, String oldPassword, String newPassword) {
        requireNewPassword(newPassword);
        transactions.executeWithoutResult(status -> {
            List<AccountRow> accounts = jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM sc_account a WHERE a.id=? FOR UPDATE", this::account, actorId);
            if (accounts.size() != 1 || !active(accounts.getFirst())) throw unauthorized();
            AccountRow row = accounts.getFirst();
            if (!passwords.verify(oldPassword, row.passwordHash())) throw invalidLogin();
            String encoded = passwords.hash(newPassword);
            if (jdbc.update("UPDATE sc_account SET password_hash=?,session_version=session_version+1 WHERE id=? AND session_version=?", encoded, actorId, row.sessionVersion()) != 1)
                throw fault("41000", 409, "Account state changed; retry after signing in");
            jdbc.update("DELETE FROM sc_session WHERE account_id=?", actorId);
        });
    }

    private boolean active(AccountRow row) {
        if (!row.enabled() || row.sessionVersion() < 1) return false;
        if (row.workspaceId() == null) return row.role() == Role.ADMIN;
        List<Boolean> flags = jdbc.queryForList("SELECT enabled FROM sc_workspace WHERE id=?", Boolean.class, row.workspaceId());
        return flags.size() == 1 && Boolean.TRUE.equals(flags.getFirst());
    }

    private AccountRow account(ResultSet result, int index) throws SQLException {
        Long workspace = result.getObject("workspace_id", Long.class);
        Role role;
        try { role = Role.valueOf(result.getString("role")); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw unauthorized(); }
        return new AccountRow(result.getLong("id"), result.getString("account_id"), result.getString("username"), result.getString("email"),
                result.getString("password_hash"), role, workspace, result.getBoolean("enabled"), result.getLong("session_version"));
    }

    private static String normalizedUsername(String username) {
        String normalized = requiredText(username, 64, "Username must contain 3 to 64 letters, digits, dots, underscores or hyphens").toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || !normalized.matches("[\\p{L}\\p{N}_.-]+") || normalized.matches("(?i)sc[0-9a-f]{12,32}"))
            throw fault("41000", 400, "Username must contain 3 to 64 letters, digits, dots, underscores or hyphens");
        return normalized;
    }

    private static String requiredText(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.trim().length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw fault("41000", 400, message);
        return value.trim();
    }

    private static void requireNewPassword(String password) {
        if (!PasswordHasher.validNewPassword(password)) throw fault("41000", 400, "Password must contain 8 to 128 characters");
    }

    private static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }

    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Session hashing is unavailable", unavailable); }
    }

    private static ApplicationFault unauthorized() { return fault("42000", 401, "Please sign in again"); }
    private static ApplicationFault invalidLogin() { return fault("30000", 401, "The account or password is incorrect"); }
    private static ApplicationFault fault(String code, int status, String message) { return new ApplicationFault(code, status, message); }

    private record SessionRow(AccountRow account, long issuedVersion, long expiresAt) { }
    private record AccountRow(long id, String accountId, String username, String email, String passwordHash, Role role,
                              Long workspaceId, boolean enabled, long sessionVersion) {
        AccountView view() { return new AccountView(id, accountId, username, email, role, workspaceId, enabled); }
        Actor actor() { return new Actor(id, accountId, username, email, role, workspaceId); }
    }
}
