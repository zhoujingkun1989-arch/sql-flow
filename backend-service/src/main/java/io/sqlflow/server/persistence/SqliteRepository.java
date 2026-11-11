package io.sqlflow.server.persistence;

import io.sqlflow.contracts.domain.GroupInfo;
import io.sqlflow.contracts.domain.ProductInfo;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.security.SqlflowBusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Primary SQLite relational persistence store ({@code conf/sqlflow.sqlite}) managing the exact
 * 11 application tables from {@code 02 §7.2.1} (omitting the broken {@code action} index on
 * {@code sqlflow_users}) and seeded built-in accounts ({@code 1}, {@code 2}, {@code 10000}, {@code 10001}).
 */
@Repository
public class SqliteRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteRepository.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)\\S{8,}$");

    private static final String[] SCHEMA_DDL = {
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_coupons` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` CHAR(64) NULL DEFAULT NULL,
          `code` CHAR(64) NOT NULL,
          `expire_date` DATETIME NOT NULL,
          `period` INTEGER NOT NULL DEFAULT 0,
          `use_date` DATETIME NULL DEFAULT NULL,
          `user_id` INTEGER NULL DEFAULT NULL,
          `user_email` CHAR(64) NULL DEFAULT NULL,
          `flag` CHAR(1) NULL DEFAULT '0',
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP
        )
        """,
        "CREATE INDEX IF NOT EXISTS `coupons_user_id` ON `sqlflow_coupons` (`user_id`)",
        "CREATE INDEX IF NOT EXISTS `coupons_user_email` ON `sqlflow_coupons` (`user_email`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `code` ON `sqlflow_coupons` (`code`)",
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_groups` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` CHAR(32) NULL DEFAULT NULL,
          `quote` INTEGER NULL DEFAULT NULL,
          `begin_date` DATETIME NULL DEFAULT NULL,
          `end_date` DATETIME NULL DEFAULT NULL,
          `flag` INTEGER NULL DEFAULT 0,
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_orders` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `order_id` CHAR(64) NOT NULL,
          `product_id` INTEGER NULL DEFAULT NULL,
          `user_id` INTEGER NULL DEFAULT NULL,
          `user_email` CHAR(64) NOT NULL DEFAULT '',
          `first_name` CHAR(32) NULL DEFAULT NULL,
          `last_name` CHAR(32) NULL DEFAULT NULL,
          `company` CHAR(128) NULL DEFAULT NULL,
          `upgrade` CHAR(1) NULL DEFAULT '0',
          `upgrade_before_date` DATETIME NULL DEFAULT NULL,
          `upgrade_after_date` DATETIME NULL DEFAULT NULL,
          `status` CHAR(1) NULL DEFAULT '0',
          `order_date` DATETIME NULL DEFAULT NULL,
          `callback_date` DATETIME NULL DEFAULT NULL,
          `flag` CHAR(1) NULL DEFAULT '0',
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `upgrade_email` CHAR(64) NULL DEFAULT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS `orders_user_id` ON `sqlflow_orders` (`user_id`)",
        "CREATE INDEX IF NOT EXISTS `orders_user_email` ON `sqlflow_orders` (`user_email`)",
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_product` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` CHAR(32) DEFAULT NULL,
          `description` CHAR(64) DEFAULT NULL,
          `price` INTEGER NULL DEFAULT NULL,
          `price_display` CHAR(16) DEFAULT NULL,
          `period` INTEGER NULL DEFAULT NULL,
          `period_unit` CHAR(1) DEFAULT NULL,
          `period_display` CHAR(32) DEFAULT NULL,
          `selected` INTEGER NULL DEFAULT NULL,
          `recommend` INTEGER NULL DEFAULT NULL,
          `flag` CHAR(1) DEFAULT '0',
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_products` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` CHAR(255) NULL DEFAULT NULL,
          `flag` INTEGER NULL DEFAULT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_trace_log` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `action` CHAR(255) NULL DEFAULT NULL,
          `user_id` CHAR(255) NULL DEFAULT NULL,
          `job_id` CHAR(255) NULL DEFAULT NULL,
          `share_id` CHAR(255) NULL DEFAULT NULL,
          `ip` CHAR(255) NULL DEFAULT NULL,
          `browser` CHAR(255) NULL DEFAULT NULL,
          `os` CHAR(255) NULL DEFAULT NULL,
          `use_method` CHAR(20) NULL DEFAULT NULL,
          `log_time` DATETIME NULL DEFAULT CURRENT_TIMESTAMP
        )
        """,
        "CREATE INDEX IF NOT EXISTS `trace_user_id` ON `sqlflow_trace_log` (`user_id`)",
        "CREATE INDEX IF NOT EXISTS `action` ON `sqlflow_trace_log` (`action`)",
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_users` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `user_id` CHAR(50) NOT NULL,
          `user_source` CHAR(50) NULL DEFAULT NULL,
          `user_name` CHAR(100) NULL DEFAULT NULL,
          `user_email` CHAR(100) NULL DEFAULT NULL,
          `password` CHAR(100) NULL DEFAULT NULL,
          `secret_key` CHAR(512) NULL DEFAULT NULL,
          `secret_key_generate_date` DATETIME NULL DEFAULT NULL,
          `register_date` DATETIME NULL DEFAULT NULL,
          `latest_login_date` DATETIME NULL DEFAULT NULL,
          `expire_date` DATETIME NULL DEFAULT NULL,
          `user_ip` CHAR(30) NULL DEFAULT NULL,
          `team_id` INTEGER NULL DEFAULT NULL,
          `role` INTEGER NULL DEFAULT NULL,
          `status` INTEGER NULL DEFAULT NULL,
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `flag` INTEGER NULL DEFAULT 0
        )
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS `user_email` ON `sqlflow_users` (`user_email`)",
        "CREATE INDEX IF NOT EXISTS `user_id` ON `sqlflow_users` (`user_id`)",
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_users_ext` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `user_id` INTEGER NULL DEFAULT NULL,
          `user_type` CHAR(32) NULL DEFAULT 'standard',
          `begin_date` DATETIME NULL DEFAULT NULL,
          `end_date` DATETIME NULL DEFAULT NULL,
          `remaining_sqls` INTEGER NULL DEFAULT 300,
          `status` INTEGER NULL DEFAULT 0,
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `flag` INTEGER NULL DEFAULT 0,
          `user_role` INTEGER NULL DEFAULT 4,
          `user_group` INTEGER NULL DEFAULT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS `users_ext_user_id` ON `sqlflow_users_ext` (`user_id`)",
        """
        CREATE TABLE IF NOT EXISTS `sqlflow_user_request` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `user_id` CHAR(255) NULL DEFAULT NULL,
          `group_id` CHAR(255) NULL DEFAULT NULL,
          `job_id` CHAR(255) NULL DEFAULT NULL,
          `batch_id` CHAR(255) NULL DEFAULT NULL,
          `ip` CHAR(255) NULL DEFAULT NULL,
          `browser` CHAR(255) NULL DEFAULT NULL,
          `os` CHAR(255) NULL DEFAULT NULL,
          `use_method` CHAR(20) NULL DEFAULT NULL,
          `mode` CHAR(255) NULL DEFAULT NULL,
          `query_id` CHAR(255) NULL DEFAULT NULL,
          `file_name` CHAR(255) NULL DEFAULT NULL,
          `file_index` INTEGER NULL DEFAULT 0,
          `sql_size` INTEGER NULL DEFAULT NULL,
          `vendor` CHAR(255) NULL DEFAULT NULL,
          `function` CHAR(255) NULL DEFAULT NULL,
          `start_time` DATETIME NULL DEFAULT NULL,
          `end_time` DATETIME NULL DEFAULT NULL,
          `status` CHAR(1) NULL DEFAULT NULL,
          `flag` CHAR(1) NULL DEFAULT '0',
          `create_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `update_date` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
          `api` CHAR(255) NULL DEFAULT NULL,
          `cost` BIGINT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS `req_user_id` ON `sqlflow_user_request` (`user_id`)",
        "CREATE INDEX IF NOT EXISTS `job_id` ON `sqlflow_user_request` (`job_id`)",
        "CREATE INDEX IF NOT EXISTS `group_id` ON `sqlflow_user_request` (`group_id`)",
        "CREATE INDEX IF NOT EXISTS `start_time` ON `sqlflow_user_request` (`start_time`)",
        """
        CREATE TABLE IF NOT EXISTS sqlflow_api_billing_rule (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          api CHAR(255) NOT NULL,
          billing_strategy CHAR(50) NOT NULL DEFAULT 'BY_SQL_SIZE',
          unit_price INTEGER NOT NULL DEFAULT 0,
          flag CHAR(1) DEFAULT '0',
          create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
          update_date DATETIME DEFAULT CURRENT_TIMESTAMP
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sqlflow_api_quote (
          id             INTEGER PRIMARY KEY AUTOINCREMENT,
          api            CHAR(255) NOT NULL UNIQUE,
          standard_quote INTEGER   NOT NULL,
          premium_quote  INTEGER   NOT NULL,
          create_date    DATETIME DEFAULT CURRENT_TIMESTAMP,
          update_date    DATETIME DEFAULT CURRENT_TIMESTAMP
        )
        """
    };

    private final SqlflowRuntimeConfig config;
    private final CryptoService cryptoService;
    private Connection sharedConn;
    private Path initializedPath;

    public SqliteRepository(SqlflowRuntimeConfig config, CryptoService cryptoService) {
        this.config = config;
        this.cryptoService = cryptoService;
    }

    @PostConstruct
    public synchronized void init() {
        ensureInitialized();
    }

    @PreDestroy
    public synchronized void close() {
        if (sharedConn != null) {
            try {
                sharedConn.close();
            } catch (SQLException ignored) {
            }
            sharedConn = null;
        }
    }

    private synchronized Connection getConnection() throws SQLException {
        Path sqlitePath = config.getSqlitePath();
        if (sharedConn != null && !sharedConn.isClosed() && sqlitePath.equals(initializedPath)) {
            return sharedConn;
        }
        close();
        try {
            if (sqlitePath.getParent() != null) {
                Files.createDirectories(sqlitePath.getParent());
            }
        } catch (Exception e) {
            throw new SQLException("Failed to create SQLite directory: " + sqlitePath, e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            for (String ddl : SCHEMA_DDL) {
                st.execute(ddl);
            }
            seedDefaults(st);
        }
        this.sharedConn = conn;
        this.initializedPath = sqlitePath;
        return conn;
    }

    public synchronized void ensureInitialized() {
        try {
            getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite schema at " + config.getSqlitePath(), e);
        }
    }

    private void seedDefaults(Statement st) throws SQLException {
        // Seed V2 & V3 users
        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users` (`id`, `user_id`, `user_source`, `user_name`, `user_email`, `password`, `register_date`, `create_date`, `update_date`, `flag`)
                VALUES (1, '21232f297a57a5a743894a0e4a801fc3', 'premise', 'admin', 'admin@sqlflow.com', '21232f297a57a5a743894a0e4a801fc3', datetime(), datetime(), datetime(), 0)
                """);
        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users_ext` (`id`, `user_id`, `user_type`, `remaining_sqls`, `status`, `create_date`, `update_date`, `flag`, `user_role`)
                VALUES (1, 1, 'premium', 300, 0, datetime(), datetime(), 0, 1)
                """);

        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users` (`id`, `user_id`, `user_source`, `user_name`, `create_date`, `update_date`, `flag`)
                VALUES (2, 'gudu|0123456789', 'default', 'anonymous', datetime(), datetime(), 0)
                """);
        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users_ext` (`id`, `user_id`, `user_type`, `remaining_sqls`, `status`, `create_date`, `update_date`, `flag`, `user_role`)
                VALUES (2, 2, 'premium', 300, 0, datetime(), datetime(), 0, 4)
                """);

        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users` (`id`, `user_id`, `user_source`, `user_name`, `user_email`, `password`, `register_date`, `create_date`, `update_date`, `flag`)
                VALUES (10000, 'c427e50ca7acbf0fe86cc7edfbf1097c', 'premise', 'admin', 'admin@local.gudusoft.com', '21232f297a57a5a743894a0e4a801fc3', datetime(), datetime(), datetime(), 0)
                """);
        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users_ext` (`id`, `user_id`, `user_type`, `remaining_sqls`, `status`, `create_date`, `update_date`, `flag`, `user_role`)
                VALUES (10000, 10000, 'premium', 300, 0, datetime(), datetime(), 0, 1)
                """);

        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users` (`id`, `user_id`, `user_source`, `user_name`, `user_email`, `password`, `register_date`, `create_date`, `update_date`, `flag`)
                VALUES (10001, '90204d5cff5eeced5d036ee5a2fec421', 'premise', 'user', 'user@local.gudusoft.com', 'ee11cbb19052e40b07aac0ca060c23ee', datetime(), datetime(), datetime(), 0)
                """);
        st.execute("""
                INSERT OR IGNORE INTO `sqlflow_users_ext` (`id`, `user_id`, `user_type`, `remaining_sqls`, `status`, `create_date`, `update_date`, `flag`, `user_role`)
                VALUES (10001, 10001, 'premium', 300, 0, datetime(), datetime(), 0, 4)
                """);

        // Seed V5 API quote
        st.execute("""
                INSERT OR IGNORE INTO sqlflow_api_quote (api, standard_quote, premium_quote)
                VALUES ('/demo/syntax/check', 10485760, 104857600)
                """);

        // Seed default products if empty
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlflow_product")) {
            if (rs.next() && rs.getInt(1) == 0) {
                st.execute("""
                        INSERT INTO sqlflow_product (id, name, description, price, price_display, period, period_unit, period_display, selected, recommend, flag)
                        VALUES
                          (1, 'sqlflow-7-days-premium-account', '7 Days Premium Access', 1990, '19.9', 7, 'D', '7 Days', 0, 0, '0'),
                          (2, 'sqlflow-premium-account-monthly-subscription', 'Monthly Premium Subscription', 4900, '49.0', 30, 'D', '30 Days', 1, 1, '0'),
                          (3, 'sqlflow-premium-account-yearly-subscription', 'Yearly Premium Subscription', 49900, '499.0', 365, 'D', '365 Days', 0, 0, '0')
                        """);
            }
        }
    }

    public synchronized List<String> listApplicationTables() {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list SQLite tables", e);
        }
        return names;
    }

    private static String nowStr() {
        return LocalDateTime.now().format(DT_FMT);
    }

    private static final String USER_SELECT = """
            SELECT u.id AS pk_id, u.user_id, u.user_source, u.user_name, u.user_email, u.password,
                   u.secret_key, u.register_date, u.latest_login_date, u.expire_date, u.create_date, u.flag,
                   COALESCE(e.user_type, 'standard') AS user_type,
                   e.begin_date, COALESCE(e.end_date, u.expire_date) AS end_date,
                   COALESCE(e.remaining_sqls, 300) AS remaining_sqls,
                   COALESCE(e.user_role, 4) AS user_role,
                   e.user_group,
                   g.name AS group_name
            FROM sqlflow_users u
            LEFT JOIN sqlflow_users_ext e ON u.id = e.user_id
            LEFT JOIN sqlflow_groups g ON e.user_group = g.id AND COALESCE(g.flag, 0) = 0
            """;

    private UserRecord mapUserRecord(ResultSet rs) throws SQLException {
        UserInfo info = new UserInfo();
        long pkId = rs.getLong("pk_id");
        String uid = rs.getString("user_id");
        info.setUserId(uid);
        info.setUserSource(rs.getString("user_source"));
        info.setUserName(rs.getString("user_name"));
        info.setUserEmail(rs.getString("user_email"));
        info.setSecretKey(rs.getString("secret_key"));
        info.setRegisterDate(rs.getString("register_date"));
        info.setLatestLoginDate(rs.getString("latest_login_date"));
        info.setCreateDate(rs.getString("create_date"));
        info.setUserType(rs.getString("user_type"));
        info.setBeginDate(rs.getString("begin_date"));
        info.setEndDate(rs.getString("end_date"));
        info.setRemainingSqls(String.valueOf(rs.getInt("remaining_sqls")));
        info.setUserRole(String.valueOf(rs.getInt("user_role")));
        Object grpObj = rs.getObject("user_group");
        if (grpObj != null) {
            info.setUserGroup(String.valueOf(grpObj));
        }
        String grpName = rs.getString("group_name");
        if (grpName != null) {
            info.setGroup(grpName);
        }
        int flag = rs.getInt("flag");
        info.setFlag(String.valueOf(flag));
        info.setUserIds(List.of(uid));
        return new UserRecord(pkId, info, rs.getString("password"), flag);
    }

    public synchronized UserRecord findRecordByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = getConnection().prepareStatement(USER_SELECT + " WHERE u.user_id = ? LIMIT 1")) {
            ps.setString(1, userId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUserRecord(rs);
                }
            }
        } catch (SQLException e) {
            log.warn("findRecordByUserId failed for {}: {}", userId, e.getMessage());
        }
        return null;
    }

    public synchronized UserInfo findByUserId(String userId) {
        UserRecord rec = findRecordByUserId(userId);
        if (rec == null || rec.flag() != 0) {
            return null;
        }
        return populateGroupPeerIds(rec.info());
    }

    public synchronized UserRecord findRecordByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                USER_SELECT + " WHERE LOWER(u.user_email) = LOWER(?) ORDER BY u.flag ASC, u.id ASC LIMIT 1")) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUserRecord(rs);
                }
            }
        } catch (SQLException e) {
            log.warn("findRecordByEmail failed for {}: {}", email, e.getMessage());
        }
        return null;
    }

    public synchronized UserInfo findByEmail(String email) {
        UserRecord rec = findRecordByEmail(email);
        if (rec == null || rec.flag() != 0) {
            return null;
        }
        return populateGroupPeerIds(rec.info());
    }

    public synchronized UserInfo findBySecretKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                USER_SELECT + " WHERE u.secret_key = ? AND COALESCE(u.flag, 0) = 0 LIMIT 1")) {
            ps.setString(1, secretKey.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return populateGroupPeerIds(mapUserRecord(rs).info());
                }
            }
        } catch (SQLException e) {
            log.warn("findBySecretKey failed: {}", e.getMessage());
        }
        return null;
    }

    public synchronized String resolveUserId(String userOrEmailOrId) {
        if (userOrEmailOrId == null || userOrEmailOrId.isBlank()) {
            return null;
        }
        UserInfo byId = findByUserId(userOrEmailOrId);
        if (byId != null) {
            return byId.getUserId();
        }
        UserInfo byEmail = findByEmail(userOrEmailOrId);
        if (byEmail != null) {
            return byEmail.getUserId();
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT user_id FROM sqlflow_users WHERE LOWER(user_name) = LOWER(?) AND COALESCE(flag, 0) = 0 LIMIT 1")) {
            ps.setString(1, userOrEmailOrId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    private UserInfo populateGroupPeerIds(UserInfo info) {
        if (info == null || info.getUserGroup() == null) {
            return info;
        }
        List<String> groupPeers = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT u.user_id FROM sqlflow_users u JOIN sqlflow_users_ext e ON u.id = e.user_id WHERE e.user_group = ? AND COALESCE(u.flag, 0) = 0")) {
            ps.setInt(1, Integer.parseInt(info.getUserGroup()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groupPeers.add(rs.getString(1));
                }
            }
        } catch (Exception ignored) {
        }
        info.setGroupUserIds(groupPeers);
        return info;
    }

    public static boolean passwordMatches(String storedHashOrPlain, String candidatePlainOrHash) {
        if (storedHashOrPlain == null) {
            return candidatePlainOrHash == null || candidatePlainOrHash.isEmpty();
        }
        if (candidatePlainOrHash == null) {
            return false;
        }
        String md5 = CryptoService.md5Hex(candidatePlainOrHash);
        return storedHashOrPlain.equalsIgnoreCase(md5) || storedHashOrPlain.equals(candidatePlainOrHash);
    }

    private void validatePasswordPolicyIfEnabled(String password) {
        if (password != null && config.isPasswordSecurityValidate()) {
            if (!PASSWORD_POLICY.matcher(password).matches()) {
                throw SqlflowBusinessException.badOperation(
                        "The password is too simple and insecure. It requires at least one uppercase letter, one lowercase letter, one number, and a length greater than 8 characters.");
            }
        }
    }

    /**
     * Wire Invariant #6: Dual Login + Registration on {@code POST /user/register}.
     */
    public synchronized RegisterResult registerOrLogin(
            String userId, String userName, String password, String userEmail, String source) {
        String now = nowStr();
        UserRecord existing = null;
        if (userEmail != null && !userEmail.isBlank()) {
            existing = findRecordByEmail(userEmail);
        }
        if (existing == null && userId != null && !userId.isBlank() && !UserInfo.GUEST_USER_ID.equals(userId)) {
            existing = findRecordByUserId(userId);
        }

        if (existing != null) {
            if (existing.flag() != 0) {
                throw SqlflowBusinessException.badOperation("User is deleted.");
            }
            // Verify password when stored password exists and caller supplied password (premise login)
            if (existing.passwordHash() != null && !existing.passwordHash().isBlank()) {
                if (password == null || !passwordMatches(existing.passwordHash(), password)) {
                    throw SqlflowBusinessException.badOperation("User is not exist or incorrect password.");
                }
            }
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "UPDATE sqlflow_users SET latest_login_date = ?, update_date = ? WHERE id = ?")) {
                ps.setString(1, now);
                ps.setString(2, now);
                ps.setLong(3, existing.pkId());
                ps.executeUpdate();
            } catch (SQLException ignored) {
            }
            UserInfo refreshed = findByUserId(existing.info().getUserId());
            String jwt = cryptoService.issueJwt(refreshed.getUserId(), refreshed.getUserSource(), config.getUserTokenExpireHours());
            return new RegisterResult(refreshed, false, jwt);
        }

        // Registration path
        if (userEmail == null || userEmail.isBlank()) {
            throw SqlflowBusinessException.badOperation("User is not exist or incorrect password.");
        }
        String newUid = (userId != null && !userId.isBlank() && !UserInfo.GUEST_USER_ID.equals(userId))
                ? userId.trim()
                : CryptoService.md5Hex(userEmail.trim().toLowerCase(Locale.ROOT));
        String displayName = (userName != null && !userName.isBlank())
                ? userName.trim()
                : userEmail.substring(0, Math.max(1, userEmail.indexOf('@') > 0 ? userEmail.indexOf('@') : userEmail.length()));
        String passHash = (password != null && !password.isEmpty()) ? CryptoService.md5Hex(password) : null;
        String src = (source != null && !source.isBlank()) ? source : (config.isOnPremise() ? "premise" : "cloud");
        String userType = config.isOnPremise() ? "premium" : "standard";

        try {
            long pkId;
            try (PreparedStatement ps = getConnection().prepareStatement("""
                    INSERT INTO sqlflow_users (user_id, user_source, user_name, user_email, password, register_date, latest_login_date, create_date, update_date, flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, newUid);
                ps.setString(2, src);
                ps.setString(3, displayName);
                ps.setString(4, userEmail.trim());
                ps.setString(5, passHash);
                ps.setString(6, now);
                ps.setString(7, now);
                ps.setString(8, now);
                ps.setString(9, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    pkId = keys.next() ? keys.getLong(1) : -1L;
                }
            }
            if (pkId > 0) {
                try (PreparedStatement extPs = getConnection().prepareStatement("""
                        INSERT INTO sqlflow_users_ext (user_id, user_type, remaining_sqls, status, create_date, update_date, flag, user_role)
                        VALUES (?, ?, 300, 0, ?, ?, 0, 4)
                        """)) {
                    extPs.setLong(1, pkId);
                    extPs.setString(2, userType);
                    extPs.setString(3, now);
                    extPs.setString(4, now);
                    extPs.executeUpdate();
                }
            }
            UserInfo created = findByUserId(newUid);
            String jwt = cryptoService.issueJwt(newUid, src, config.getUserTokenExpireHours());
            return new RegisterResult(created, true, jwt);
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Register user failed: " + e.getMessage());
        }
    }

    public synchronized void requireSystemAdmin(String operatorUserId) {
        UserInfo op = findByUserId(operatorUserId);
        if (op == null || !"1".equals(op.getUserRole())) {
            throw SqlflowBusinessException.forbidden("Illegal operation");
        }
    }

    public synchronized UserInfo addUser(
            String operatorUserId, String userName, String userEmail, String password, String expireDate, boolean systemAdmin) {
        requireSystemAdmin(operatorUserId);
        validatePasswordPolicyIfEnabled(password);
        if (expireDate != null && !expireDate.isBlank()) {
            try {
                LocalDate.parse(expireDate.trim(), DATE_FMT);
            } catch (Exception e) {
                throw SqlflowBusinessException.badOperation("The expire date format is not yyyy-MM-dd.");
            }
        }
        UserRecord existing = findRecordByEmail(userEmail);
        if (existing != null && existing.flag() == 0) {
            throw SqlflowBusinessException.badOperation("User email already exists.");
        }
        String now = nowStr();
        String uid = CryptoService.md5Hex(userEmail.trim().toLowerCase(Locale.ROOT) + ":" + UUID.randomUUID());
        int role = systemAdmin ? 1 : 4;
        String endDate = (expireDate != null && !expireDate.isBlank()) ? expireDate.trim() + " 23:59:59" : null;
        try {
            if (existing != null) {
                // Reactivate soft-deleted email row
                try (PreparedStatement ps = getConnection().prepareStatement("""
                        UPDATE sqlflow_users SET user_id = ?, user_name = ?, password = ?, expire_date = ?, update_date = ?, flag = 0 WHERE id = ?
                        """)) {
                    ps.setString(1, uid);
                    ps.setString(2, userName);
                    ps.setString(3, CryptoService.md5Hex(password));
                    ps.setString(4, endDate);
                    ps.setString(5, now);
                    ps.setLong(6, existing.pkId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = getConnection().prepareStatement("""
                        UPDATE sqlflow_users_ext SET user_type = 'premium', end_date = ?, user_role = ?, flag = 0, update_date = ? WHERE user_id = ?
                        """)) {
                    ps.setString(1, endDate);
                    ps.setInt(2, role);
                    ps.setString(3, now);
                    ps.setLong(4, existing.pkId());
                    ps.executeUpdate();
                }
            } else {
                long pkId;
                try (PreparedStatement ps = getConnection().prepareStatement("""
                        INSERT INTO sqlflow_users (user_id, user_source, user_name, user_email, password, expire_date, register_date, create_date, update_date, flag)
                        VALUES (?, 'premise', ?, ?, ?, ?, ?, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, uid);
                    ps.setString(2, userName);
                    ps.setString(3, userEmail.trim());
                    ps.setString(4, CryptoService.md5Hex(password));
                    ps.setString(5, endDate);
                    ps.setString(6, now);
                    ps.setString(7, now);
                    ps.setString(8, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        pkId = keys.next() ? keys.getLong(1) : -1L;
                    }
                }
                try (PreparedStatement ps = getConnection().prepareStatement("""
                        INSERT INTO sqlflow_users_ext (user_id, user_type, end_date, remaining_sqls, status, create_date, update_date, flag, user_role)
                        VALUES (?, 'premium', ?, 300, 0, ?, ?, 0, ?)
                        """)) {
                    ps.setLong(1, pkId);
                    ps.setString(2, endDate);
                    ps.setString(3, now);
                    ps.setString(4, now);
                    ps.setInt(5, role);
                    ps.executeUpdate();
                }
            }
            return findByUserId(uid);
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Add user failed: " + e.getMessage());
        }
    }

    public synchronized UserInfo updateUser(
            String operatorUserId, String userName, String userEmail, String password, String expireDate, Boolean systemAdmin) {
        requireSystemAdmin(operatorUserId);
        if (password != null && !password.isBlank()) {
            validatePasswordPolicyIfEnabled(password);
        }
        UserRecord rec = findRecordByEmail(userEmail);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        String now = nowStr();
        String endDate = (expireDate != null && !expireDate.isBlank()) ? expireDate.trim() : rec.info().getEndDate();
        try {
            try (PreparedStatement ps = getConnection().prepareStatement("""
                    UPDATE sqlflow_users SET user_name = COALESCE(?, user_name), password = COALESCE(?, password), expire_date = ?, update_date = ? WHERE id = ?
                    """)) {
                ps.setString(1, (userName != null && !userName.isBlank()) ? userName : null);
                ps.setString(2, (password != null && !password.isBlank()) ? CryptoService.md5Hex(password) : null);
                ps.setString(3, endDate);
                ps.setString(4, now);
                ps.setLong(5, rec.pkId());
                ps.executeUpdate();
            }
            if (systemAdmin != null || endDate != null) {
                int newRole = systemAdmin != null ? (systemAdmin ? 1 : 4) : Integer.parseInt(rec.info().getUserRole());
                try (PreparedStatement ps = getConnection().prepareStatement("""
                        UPDATE sqlflow_users_ext SET end_date = ?, user_role = ?, update_date = ? WHERE user_id = ?
                        """)) {
                    ps.setString(1, endDate);
                    ps.setInt(2, newRole);
                    ps.setString(3, now);
                    ps.setLong(4, rec.pkId());
                    ps.executeUpdate();
                }
            }
            return findByUserId(rec.info().getUserId());
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Update user failed: " + e.getMessage());
        }
    }

    public synchronized String deleteUserByEmail(String operatorUserId, String userEmail) {
        requireSystemAdmin(operatorUserId);
        UserRecord target = findRecordByEmail(userEmail);
        if (target == null || target.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        if (Objects.equals(operatorUserId, target.info().getUserId())) {
            throw SqlflowBusinessException.badOperation("Operator cannot delete self.");
        }
        if (target.pkId() == 10000L || target.pkId() == 1L) {
            throw SqlflowBusinessException.badOperation("Operators cannot delete the built-in system manager.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users SET flag = 1, update_date = ? WHERE id = ?")) {
            ps.setString(1, nowStr());
            ps.setLong(2, target.pkId());
            ps.executeUpdate();
            return target.info().getUserId();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Delete user failed: " + e.getMessage());
        }
    }

    public synchronized void resetPassword(String operatorUserId, String userEmail, String newPassword) {
        requireSystemAdmin(operatorUserId);
        validatePasswordPolicyIfEnabled(newPassword);
        UserRecord target = findRecordByEmail(userEmail);
        if (target == null || target.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users SET password = ?, update_date = ? WHERE id = ?")) {
            ps.setString(1, CryptoService.md5Hex(newPassword));
            ps.setString(2, nowStr());
            ps.setLong(3, target.pkId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Reset password failed: " + e.getMessage());
        }
    }

    public synchronized void modifyPassword(String userId, String oldPassword, String newPassword) {
        validatePasswordPolicyIfEnabled(newPassword);
        UserRecord rec = findRecordByUserId(userId);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        if (rec.passwordHash() != null && !passwordMatches(rec.passwordHash(), oldPassword)) {
            throw SqlflowBusinessException.badOperation("Password is wrong.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users SET password = ?, update_date = ? WHERE id = ?")) {
            ps.setString(1, CryptoService.md5Hex(newPassword != null ? newPassword : ""));
            ps.setString(2, nowStr());
            ps.setLong(3, rec.pkId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Modify password failed: " + e.getMessage());
        }
    }

    public synchronized void unregister(String userId, String userEmail, String operatorUserId) {
        UserRecord target = findRecordByUserId(userId);
        if (target == null) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        if (target.pkId() == 10000L || target.pkId() == 1L) {
            throw SqlflowBusinessException.badOperation("The built-in system manager can't be deleted.");
        }
        if (operatorUserId != null && !operatorUserId.isBlank() && !Objects.equals(operatorUserId, userId)) {
            requireSystemAdmin(operatorUserId);
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users SET flag = 1, update_date = ? WHERE id = ?")) {
            ps.setString(1, nowStr());
            ps.setLong(2, target.pkId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Unregister failed: " + e.getMessage());
        }
    }

    public synchronized String generateSecretKey(String userId) {
        UserRecord rec = findRecordByUserId(userId);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.invalidUser("Invalid user.");
        }
        String sk = cryptoService.generateSecretKey();
        String now = nowStr();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users SET secret_key = ?, secret_key_generate_date = ?, update_date = ? WHERE id = ?")) {
            ps.setString(1, sk);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.setLong(4, rec.pkId());
            ps.executeUpdate();
            return sk;
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Generate secret key failed: " + e.getMessage());
        }
    }

    public synchronized UserInfo upgradeUser(String operatorUserId, String targetUserId, int renewalDays) {
        if (operatorUserId != null && !operatorUserId.isBlank()) {
            requireSystemAdmin(operatorUserId);
        }
        UserRecord rec = findRecordByUserId(targetUserId);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        LocalDate newEnd = LocalDate.now().plusDays(Math.max(1, renewalDays));
        String endStr = newEnd.format(DATE_FMT) + " 23:59:59";
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users_ext SET user_type = 'premium', end_date = ?, update_date = ? WHERE user_id = ?")) {
            ps.setString(1, endStr);
            ps.setString(2, nowStr());
            ps.setLong(3, rec.pkId());
            ps.executeUpdate();
            return findByUserId(targetUserId);
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Upgrade user failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> listUsersByType(String userType) {
        List<UserInfo> users = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                USER_SELECT + " WHERE COALESCE(u.flag, 0) = 0 AND e.user_type = ? ORDER BY u.id ASC")) {
            ps.setString(1, userType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(mapUserRecord(rs).info());
                }
            }
        } catch (SQLException ignored) {
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", users.size());
        res.put("users", users);
        return res;
    }

    public synchronized Map<String, Object> queryUsers(Map<String, Object> query) {
        int current = intParam(query, "current", intParam(query, "pageNum", 1));
        int size = intParam(query, "size", intParam(query, "pageSize", 20));
        String filterUser = strParam(query, "user");
        List<UserInfo> all = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                USER_SELECT + " WHERE COALESCE(u.flag, 0) = 0 ORDER BY u.id ASC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UserInfo u = mapUserRecord(rs).info();
                if (filterUser != null && !filterUser.isBlank()) {
                    String q = filterUser.toLowerCase(Locale.ROOT);
                    boolean match = (u.getUserEmail() != null && u.getUserEmail().toLowerCase(Locale.ROOT).contains(q))
                            || (u.getUserName() != null && u.getUserName().toLowerCase(Locale.ROOT).contains(q))
                            || (u.getUserId() != null && u.getUserId().toLowerCase(Locale.ROOT).contains(q));
                    if (!match) {
                        continue;
                    }
                }
                all.add(u);
            }
        } catch (SQLException ignored) {
        }
        return paginate(all, current, size);
    }

    // --- Group Operations ---

    public synchronized GroupInfo createGroup(
            String operatorUserId, String groupName, int quote, String createdDate, String expireDate, String teamManagerEmail) {
        requireSystemAdmin(operatorUserId);
        if (quote <= 0) {
            throw SqlflowBusinessException.badOperation("Group quote should be a positive integer.");
        }
        String now = nowStr();
        String begin = (createdDate != null && !createdDate.isBlank()) ? createdDate : now;
        String end = (expireDate != null && !expireDate.isBlank()) ? expireDate : LocalDate.now().plusYears(1).format(DATE_FMT);
        try {
            long groupId;
            try (PreparedStatement ps = getConnection().prepareStatement("""
                    INSERT INTO sqlflow_groups (name, quote, begin_date, end_date, flag, create_date, update_date)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, groupName);
                ps.setInt(2, quote);
                ps.setString(3, begin);
                ps.setString(4, end);
                ps.setString(5, now);
                ps.setString(6, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    groupId = keys.next() ? keys.getLong(1) : 1L;
                }
            }
            if (teamManagerEmail != null && !teamManagerEmail.isBlank()) {
                UserRecord mgr = findRecordByEmail(teamManagerEmail);
                if (mgr != null && mgr.flag() == 0) {
                    try (PreparedStatement ps = getConnection().prepareStatement(
                            "UPDATE sqlflow_users_ext SET user_group = ?, user_role = 2, user_type = 'premium', end_date = ? WHERE user_id = ?")) {
                        ps.setLong(1, groupId);
                        ps.setString(2, end);
                        ps.setLong(3, mgr.pkId());
                        ps.executeUpdate();
                    }
                }
            }
            return getGroup(operatorUserId, String.valueOf(groupId));
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Create group failed: " + e.getMessage());
        }
    }

    public synchronized void checkGroupPermission(String operatorUserId, String groupId, String... allowedRoles) {
        UserInfo op = findByUserId(operatorUserId);
        if (op == null) {
            throw SqlflowBusinessException.forbidden("Illegal operation");
        }
        if ("1".equals(op.getUserRole())) {
            return;
        }
        if (groupId != null && Objects.equals(groupId, op.getUserGroup())) {
            for (String allowed : allowedRoles) {
                if (Objects.equals(allowed, op.getUserRole())) {
                    return;
                }
            }
        }
        throw SqlflowBusinessException.forbidden("Illegal operation");
    }

    public synchronized GroupInfo getGroup(String operatorUserId, String groupId) {
        checkGroupPermission(operatorUserId, groupId, "1", "2", "3");
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT id, name, quote, begin_date, end_date, create_date FROM sqlflow_groups WHERE id = ? AND COALESCE(flag, 0) = 0")) {
            ps.setLong(1, Long.parseLong(groupId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw SqlflowBusinessException.badOperation("Group does not exist or has been deleted.");
                }
                GroupInfo g = new GroupInfo();
                String idStr = String.valueOf(rs.getLong("id"));
                g.setGroupId(idStr);
                g.setUserGroup(idStr);
                g.setGroupName(rs.getString("name"));
                g.setQuote(String.valueOf(rs.getInt("quote")));
                g.setCreatedDate(rs.getString("begin_date") != null ? rs.getString("begin_date") : rs.getString("create_date"));
                g.setExpiredDate(rs.getString("end_date"));
                g.setGroupUsers(loadGroupMembers(rs.getLong("id")));
                return g;
            }
        } catch (NumberFormatException | SQLException e) {
            throw SqlflowBusinessException.badOperation("Group does not exist or has been deleted.");
        }
    }

    private List<GroupInfo.GroupMember> loadGroupMembers(long groupId) throws SQLException {
        List<GroupInfo.GroupMember> members = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement("""
                SELECT u.user_id, u.user_name, u.user_email, e.user_role
                FROM sqlflow_users u JOIN sqlflow_users_ext e ON u.id = e.user_id
                WHERE e.user_group = ? AND COALESCE(u.flag, 0) = 0
                """)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GroupInfo.GroupMember m = new GroupInfo.GroupMember(
                            rs.getString("user_email"),
                            String.valueOf(rs.getInt("user_role")));
                    m.setUserId(rs.getString("user_id"));
                    m.setUserName(rs.getString("user_name"));
                    m.setGroupAdmin(rs.getInt("user_role") == 2);
                    members.add(m);
                }
            }
        }
        return members;
    }

    public synchronized List<GroupInfo> listGroups(String operatorUserId) {
        requireSystemAdmin(operatorUserId);
        List<GroupInfo> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT id, name, quote, begin_date, end_date, create_date FROM sqlflow_groups WHERE COALESCE(flag, 0) = 0 ORDER BY id DESC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                GroupInfo g = new GroupInfo();
                String idStr = String.valueOf(rs.getLong("id"));
                g.setGroupId(idStr);
                g.setUserGroup(idStr);
                g.setGroupName(rs.getString("name"));
                g.setQuote(String.valueOf(rs.getInt("quote")));
                g.setCreatedDate(rs.getString("begin_date") != null ? rs.getString("begin_date") : rs.getString("create_date"));
                g.setExpiredDate(rs.getString("end_date"));
                g.setGroupUsers(loadGroupMembers(rs.getLong("id")));
                list.add(g);
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    public synchronized void deleteGroup(String operatorUserId, String groupId) {
        requireSystemAdmin(operatorUserId);
        long gid = Long.parseLong(groupId);
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "UPDATE sqlflow_groups SET flag = 1, update_date = ? WHERE id = ?")) {
                ps.setString(1, nowStr());
                ps.setLong(2, gid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = getConnection().prepareStatement(
                    "UPDATE sqlflow_users_ext SET user_group = NULL, user_role = CASE WHEN user_role = 1 THEN 1 ELSE 4 END WHERE user_group = ?")) {
                ps.setLong(1, gid);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Delete group failed: " + e.getMessage());
        }
    }

    public synchronized GroupInfo setGroupName(String operatorUserId, String groupId, String groupName) {
        checkGroupPermission(operatorUserId, groupId, "1", "2");
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_groups SET name = ?, update_date = ? WHERE id = ?")) {
            ps.setString(1, groupName);
            ps.setString(2, nowStr());
            ps.setLong(3, Long.parseLong(groupId));
            ps.executeUpdate();
            return getGroup(operatorUserId, groupId);
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Set group name failed: " + e.getMessage());
        }
    }

    public synchronized GroupInfo upgradeGroup(
            String operatorUserId, String groupId, Integer quote, String expireDate, Integer renewalDays, String groupName) {
        requireSystemAdmin(operatorUserId);
        GroupInfo current = getGroup(operatorUserId, groupId);
        int memberCount = current.getGroupUsers() != null ? current.getGroupUsers().size() : 0;
        int newQuote = quote != null ? quote : Integer.parseInt(current.getQuote());
        if (newQuote < memberCount) {
            throw SqlflowBusinessException.badOperation(
                    "Group quote should be greater than or equal to the number of current group members.");
        }
        String newExpire = expireDate;
        if ((newExpire == null || newExpire.isBlank()) && renewalDays != null) {
            newExpire = LocalDate.now().plusDays(renewalDays).format(DATE_FMT);
        }
        if (newExpire == null || newExpire.isBlank()) {
            newExpire = current.getExpiredDate();
        }
        String newName = (groupName != null && !groupName.isBlank()) ? groupName : current.getGroupName();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_groups SET name = ?, quote = ?, end_date = ?, update_date = ? WHERE id = ?")) {
            ps.setString(1, newName);
            ps.setInt(2, newQuote);
            ps.setString(3, newExpire);
            ps.setString(4, nowStr());
            ps.setLong(5, Long.parseLong(groupId));
            ps.executeUpdate();
            return getGroup(operatorUserId, groupId);
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Upgrade group failed: " + e.getMessage());
        }
    }

    public synchronized void addGroupUser(String operatorUserId, String groupId, String userEmail, String userRole) {
        checkGroupPermission(operatorUserId, groupId, "1", "2");
        GroupInfo g = getGroup(operatorUserId, groupId);
        int currentCount = g.getGroupUsers() != null ? g.getGroupUsers().size() : 0;
        if (currentCount >= Integer.parseInt(g.getQuote())) {
            throw SqlflowBusinessException.badOperation("The group users count has exceeded the quote limit.");
        }
        UserRecord target = findRecordByEmail(userEmail);
        if (target == null || target.flag() != 0) {
            throw SqlflowBusinessException.badOperation("User does not exist or has been deleted.");
        }
        int role = ("2".equals(userRole) || "3".equals(userRole)) ? Integer.parseInt(userRole) : 3;
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users_ext SET user_group = ?, user_role = ?, user_type = 'premium', end_date = ?, update_date = ? WHERE user_id = ?")) {
            ps.setLong(1, Long.parseLong(groupId));
            ps.setInt(2, role);
            ps.setString(3, g.getExpiredDate());
            ps.setString(4, nowStr());
            ps.setLong(5, target.pkId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Add group user failed: " + e.getMessage());
        }
    }

    public synchronized void removeGroupUser(String operatorUserId, String groupId, String userEmail) {
        checkGroupPermission(operatorUserId, groupId, "1", "2");
        UserRecord target = findRecordByEmail(userEmail);
        if (target == null || target.flag() != 0) {
            throw SqlflowBusinessException.badOperation("User does not exist or has been deleted.");
        }
        if (Objects.equals(operatorUserId, target.info().getUserId()) && "2".equals(target.info().getUserRole())) {
            throw SqlflowBusinessException.badOperation("Can't delete manager self from group.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users_ext SET user_group = NULL, user_role = CASE WHEN user_role = 1 THEN 1 ELSE 4 END, update_date = ? WHERE user_id = ?")) {
            ps.setString(1, nowStr());
            ps.setLong(2, target.pkId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Remove group user failed: " + e.getMessage());
        }
    }

    public synchronized void updateGroupUserRole(String operatorUserId, String groupId, String userEmail, String userRole) {
        checkGroupPermission(operatorUserId, groupId, "1", "2");
        if (!"2".equals(userRole) && !"3".equals(userRole)) {
            throw SqlflowBusinessException.badOperation("Invalid user role.");
        }
        UserRecord target = findRecordByEmail(userEmail);
        if (target == null || target.flag() != 0) {
            throw SqlflowBusinessException.badOperation("User does not exist or has been deleted.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users_ext SET user_role = ?, update_date = ? WHERE user_id = ? AND user_group = ?")) {
            ps.setInt(1, Integer.parseInt(userRole));
            ps.setString(2, nowStr());
            ps.setLong(3, target.pkId());
            ps.setLong(4, Long.parseLong(groupId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Update group user role failed: " + e.getMessage());
        }
    }

    public synchronized void syncGroupUsers(String operatorUserId, String groupId) {
        checkGroupPermission(operatorUserId, groupId, "1", "2");
        GroupInfo g = getGroup(operatorUserId, groupId);
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_users_ext SET user_type = 'premium', end_date = ?, update_date = ? WHERE user_group = ?")) {
            ps.setString(1, g.getExpiredDate());
            ps.setString(2, nowStr());
            ps.setLong(3, Long.parseLong(groupId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Sync group users failed: " + e.getMessage());
        }
    }

    // --- Coupon Operations ---

    public synchronized Map<String, Object> createCoupon(
            String operatorUserId, String name, String code, String expireDate, int period) {
        requireSystemAdmin(operatorUserId);
        String now = nowStr();
        try (PreparedStatement ps = getConnection().prepareStatement("""
                INSERT INTO sqlflow_coupons (name, code, expire_date, period, flag, create_date, update_date)
                VALUES (?, ?, ?, ?, '0', ?, ?)
                """)) {
            ps.setString(1, name != null ? name : "Promo");
            ps.setString(2, code.trim());
            ps.setString(3, expireDate.trim());
            ps.setInt(4, period);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            return getCouponByCode(operatorUserId, code.trim());
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Create coupon failed.");
        }
    }

    public synchronized List<Map<String, Object>> batchCreateCoupons(
            String operatorUserId, String name, String expireDate, int period, int number) {
        requireSystemAdmin(operatorUserId);
        List<Map<String, Object>> created = new ArrayList<>();
        int count = Math.max(1, Math.min(200, number));
        for (int i = 0; i < count; i++) {
            String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
            created.add(createCoupon(operatorUserId, name, code, expireDate, period));
        }
        return created;
    }

    public synchronized Map<String, Object> getCouponByCode(String operatorUserId, String code) {
        requireSystemAdmin(operatorUserId);
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_coupons WHERE code = ? AND COALESCE(flag, '0') = '0' LIMIT 1")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapCoupon(rs);
                }
            }
        } catch (SQLException ignored) {
        }
        throw SqlflowBusinessException.badOperation("Invalid coupon code.");
    }

    public synchronized List<Map<String, Object>> listUnusedCoupons(String operatorUserId) {
        requireSystemAdmin(operatorUserId);
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_coupons WHERE COALESCE(flag, '0') = '0' AND user_id IS NULL ORDER BY id DESC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapCoupon(rs));
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    public synchronized void deleteCoupon(String operatorUserId, String code) {
        requireSystemAdmin(operatorUserId);
        Map<String, Object> c = getCouponByCode(operatorUserId, code);
        if (c.get("userId") != null) {
            throw SqlflowBusinessException.badOperation("Coupon code had been used, can't be deleted.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE sqlflow_coupons SET flag = '1', update_date = ? WHERE code = ?")) {
            ps.setString(1, nowStr());
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Delete coupon failed.");
        }
    }

    public synchronized List<Map<String, Object>> getUserCouponInfo(String operatorUserId, String userEmail) {
        requireSystemAdmin(operatorUserId);
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_coupons WHERE LOWER(user_email) = LOWER(?) AND COALESCE(flag, '0') = '0'")) {
            ps.setString(1, userEmail);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapCoupon(rs));
                }
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    public synchronized Map<String, Object> queryCoupons(Map<String, Object> query) {
        String op = strParam(query, "operatorUserId");
        if (op != null && !op.isBlank()) {
            requireSystemAdmin(op);
        }
        int current = intParam(query, "current", 1);
        int size = intParam(query, "size", 20);
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_coupons WHERE COALESCE(flag, '0') = '0' ORDER BY id DESC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapCoupon(rs));
            }
        } catch (SQLException ignored) {
        }
        return paginate(list, current, size);
    }

    public synchronized UserInfo applyUserCoupon(String userId, String couponCode) {
        UserRecord rec = findRecordByUserId(userId);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_coupons WHERE code = ? AND COALESCE(flag, '0') = '0' LIMIT 1")) {
            ps.setString(1, couponCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw SqlflowBusinessException.badOperation("Invalid coupon code.");
                }
                if (rs.getObject("user_id") != null) {
                    throw SqlflowBusinessException.badOperation("The user has already used the coupon.");
                }
                String expire = rs.getString("expire_date");
                if (expire != null && expire.length() >= 10) {
                    LocalDate expDate = LocalDate.parse(expire.substring(0, 10), DATE_FMT);
                    if (expDate.isBefore(LocalDate.now())) {
                        throw SqlflowBusinessException.badOperation("The coupon expired.");
                    }
                }
                int period = rs.getInt("period");
                String now = nowStr();
                try (PreparedStatement upd = getConnection().prepareStatement(
                        "UPDATE sqlflow_coupons SET user_id = ?, user_email = ?, use_date = ?, update_date = ? WHERE id = ?")) {
                    upd.setLong(1, rec.pkId());
                    upd.setString(2, rec.info().getUserEmail());
                    upd.setString(3, now);
                    upd.setString(4, now);
                    upd.setLong(5, rs.getLong("id"));
                    upd.executeUpdate();
                }
                return upgradeUser(null, userId, Math.max(1, period));
            }
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Apply coupon failed: " + e.getMessage());
        }
    }

    private Map<String, Object> mapCoupon(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("code", rs.getString("code"));
        m.put("expireDate", rs.getString("expire_date"));
        m.put("period", rs.getInt("period"));
        m.put("useDate", rs.getString("use_date"));
        m.put("userId", rs.getObject("user_id"));
        m.put("userEmail", rs.getString("user_email"));
        m.put("createDate", rs.getString("create_date"));
        return m;
    }

    // --- Products & Orders ---

    public synchronized List<ProductInfo> listProducts() {
        List<ProductInfo> items = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_product WHERE COALESCE(flag, '0') = '0' ORDER BY price ASC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(new ProductInfo(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("period_display"),
                        rs.getInt("recommend") == 1,
                        rs.getInt("selected") == 1,
                        rs.getString("price_display"),
                        rs.getString("description")));
            }
        } catch (SQLException ignored) {
        }
        return items;
    }

    public synchronized void recordOrder(
            String orderId, String email, String firstName, String lastName, String company, String productSku) {
        String now = nowStr();
        try (PreparedStatement ps = getConnection().prepareStatement("""
                INSERT INTO sqlflow_orders (order_id, user_email, first_name, last_name, company, status, order_date, callback_date, create_date, update_date)
                VALUES (?, ?, ?, ?, ?, '1', ?, ?, ?, ?)
                """)) {
            ps.setString(1, orderId);
            ps.setString(2, email != null ? email : "");
            ps.setString(3, firstName);
            ps.setString(4, lastName);
            ps.setString(5, company);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
        UserRecord rec = findRecordByEmail(email);
        if (rec != null) {
            int days = switch (productSku == null ? "" : productSku) {
                case "sqlflow-3-days-premium-account" -> 4;
                case "sqlflow-7-days-premium-account" -> 8;
                case "sqlflow-14-days-premium-account" -> 15;
                case "sqlflow-premium-account-yearly-subscription" -> 365;
                default -> 31;
            };
            upgradeUser(null, rec.info().getUserId(), days);
        }
    }

    public synchronized UserInfo upgradeUserByOrder(
            String operatorUserId, String userEmail, String orderEmail, String orderNumber, int renewalDays) {
        requireSystemAdmin(operatorUserId);
        UserRecord rec = findRecordByEmail(userEmail);
        if (rec == null || rec.flag() != 0) {
            throw SqlflowBusinessException.badOperation("Invalid user.");
        }
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT id, upgrade FROM sqlflow_orders WHERE order_id = ? AND LOWER(user_email) = LOWER(?) LIMIT 1")) {
            ps.setString(1, orderNumber);
            ps.setString(2, orderEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw SqlflowBusinessException.badOperation("Invalid order number or order email.");
                }
                if ("1".equals(rs.getString("upgrade"))) {
                    throw SqlflowBusinessException.badOperation("Sorry, your order number has already been used.");
                }
                try (PreparedStatement upd = getConnection().prepareStatement(
                        "UPDATE sqlflow_orders SET upgrade = '1', upgrade_email = ?, update_date = ? WHERE id = ?")) {
                    upd.setString(1, userEmail);
                    upd.setString(2, nowStr());
                    upd.setLong(3, rs.getLong("id"));
                    upd.executeUpdate();
                }
                return upgradeUser(null, rec.info().getUserId(), renewalDays);
            }
        } catch (SQLException e) {
            throw SqlflowBusinessException.badOperation("Upgrade by order failed: " + e.getMessage());
        }
    }

    // --- Audit Trace & Request Quota ---

    public synchronized void insertTraceLog(Map<String, Object> trace) {
        try (PreparedStatement ps = getConnection().prepareStatement("""
                INSERT INTO sqlflow_trace_log (action, user_id, job_id, share_id, ip, browser, os, use_method, log_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, strParam(trace, "action"));
            ps.setString(2, strParam(trace, "userId"));
            ps.setString(3, strParam(trace, "jobId"));
            ps.setString(4, strParam(trace, "shareId"));
            ps.setString(5, strParam(trace, "ip"));
            ps.setString(6, strParam(trace, "browser"));
            ps.setString(7, strParam(trace, "os"));
            ps.setString(8, strParam(trace, "useMethod"));
            ps.setString(9, nowStr());
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized long insertUserRequestStart(Map<String, Object> req) {
        String api = strParam(req, "api");
        String useMethod = strParam(req, "useMethod");
        String userId = strParam(req, "userId");
        int sqlSize = intParam(req, "sqlSize", 0);

        if ("api".equalsIgnoreCase(useMethod) && api != null && !api.isBlank()) {
            checkApiQuota(userId, api, sqlSize);
        }

        String now = nowStr();
        try (PreparedStatement ps = getConnection().prepareStatement("""
                INSERT INTO sqlflow_user_request (user_id, group_id, job_id, batch_id, ip, browser, os, use_method, mode, query_id, file_name, sql_size, vendor, function, start_time, status, api, create_date, update_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0', ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, userId);
            ps.setString(2, strParam(req, "groupId"));
            ps.setString(3, strParam(req, "jobId"));
            ps.setString(4, strParam(req, "batchId"));
            ps.setString(5, strParam(req, "ip"));
            ps.setString(6, strParam(req, "browser"));
            ps.setString(7, strParam(req, "os"));
            ps.setString(8, useMethod != null ? useMethod : "web");
            ps.setString(9, strParam(req, "mode"));
            ps.setString(10, strParam(req, "queryId"));
            ps.setString(11, strParam(req, "fileName"));
            ps.setInt(12, sqlSize);
            ps.setString(13, strParam(req, "vendor"));
            ps.setString(14, strParam(req, "function"));
            ps.setString(15, now);
            ps.setString(16, api);
            ps.setString(17, now);
            ps.setString(18, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    public synchronized void updateUserRequestEnd(Map<String, Object> req) {
        String queryId = strParam(req, "queryId");
        long id = intParam(req, "id", 0);
        String status = strParam(req, "status");
        long cost = intParam(req, "cost", 0);
        String now = nowStr();
        try {
            if (id > 0) {
                try (PreparedStatement ps = getConnection().prepareStatement(
                        "UPDATE sqlflow_user_request SET end_time = ?, status = ?, cost = ?, update_date = ? WHERE id = ?")) {
                    ps.setString(1, now);
                    ps.setString(2, status != null ? status : "1");
                    ps.setLong(3, cost);
                    ps.setString(4, now);
                    ps.setLong(5, id);
                    ps.executeUpdate();
                }
            } else if (queryId != null) {
                try (PreparedStatement ps = getConnection().prepareStatement(
                        "UPDATE sqlflow_user_request SET end_time = ?, status = ?, cost = ?, update_date = ? WHERE query_id = ?")) {
                    ps.setString(1, now);
                    ps.setString(2, status != null ? status : "1");
                    ps.setLong(3, cost);
                    ps.setString(4, now);
                    ps.setString(5, queryId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public synchronized void checkApiQuota(String userId, String api, int incomingBytes) {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT standard_quote, premium_quote FROM sqlflow_api_quote WHERE api = ? LIMIT 1")) {
            ps.setString(1, api);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                UserInfo u = findByUserId(userId);
                boolean premium = u != null && "premium".equalsIgnoreCase(u.getUserType());
                long limit = premium ? rs.getLong("premium_quote") : rs.getLong("standard_quote");
                long used = 0L;
                try (PreparedStatement sumPs = getConnection().prepareStatement("""
                        SELECT COALESCE(SUM(sql_size), 0) FROM sqlflow_user_request
                        WHERE user_id = ? AND api = ? AND use_method = 'api'
                          AND create_date >= datetime('now', '-30 days')
                        """)) {
                    sumPs.setString(1, userId);
                    sumPs.setString(2, api);
                    try (ResultSet sumRs = sumPs.executeQuery()) {
                        if (sumRs.next()) {
                            used = sumRs.getLong(1);
                        }
                    }
                }
                if (used + incomingBytes > limit) {
                    throw SqlflowBusinessException.badOperation(
                            "API " + api + " call failed: quota has been exhausted. Please contact support@gudusoft.com to upgrade your plan.");
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public synchronized Map<String, Object> queryUserRequests(Map<String, Object> query) {
        int current = intParam(query, "current", 1);
        int size = intParam(query, "size", 20);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT * FROM sqlflow_user_request ORDER BY id DESC LIMIT 500");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("userId", rs.getString("user_id"));
                m.put("jobId", rs.getString("job_id"));
                m.put("useMethod", rs.getString("use_method"));
                m.put("sqlSize", rs.getInt("sql_size"));
                m.put("vendor", rs.getString("vendor"));
                m.put("function", rs.getString("function"));
                m.put("status", rs.getString("status"));
                m.put("api", rs.getString("api"));
                m.put("createDate", rs.getString("create_date"));
                rows.add(m);
            }
        } catch (SQLException ignored) {
        }
        return paginate(rows, current, size);
    }

    private static <T> Map<String, Object> paginate(List<T> all, int current, int size) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        int from = Math.min(all.size(), (page - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        List<T> slice = all.subList(from, to);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("result", slice);
        res.put("records", slice);
        res.put("list", slice);
        res.put("total", all.size());
        res.put("currentPage", page);
        res.put("pageNum", page);
        res.put("pageSize", pageSize);
        return res;
    }

    private static String strParam(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static int intParam(Map<String, Object> map, String key, int def) {
        if (map == null) {
            return def;
        }
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    public record UserRecord(long pkId, UserInfo info, String passwordHash, int flag) {
    }

    public record RegisterResult(UserInfo user, boolean firstLogin, String token) {
    }
}
