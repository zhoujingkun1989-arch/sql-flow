package io.sqlflow.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.GroupInfo;
import io.sqlflow.contracts.domain.ProductListEnvelope;
import io.sqlflow.contracts.domain.SecretKeyResponse;
import io.sqlflow.contracts.domain.ShareInfo;
import io.sqlflow.contracts.domain.TokenResponse;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SqliteRepository;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.security.RequestAuthResolver;
import io.sqlflow.server.security.SqlflowBusinessException;
import io.sqlflow.server.service.LineageGenerationService;
import io.sqlflow.server.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * User, Auth/Credentials, MD5 User Settings, Recorded SQL Audit Logs, Group, Coupon, Order,
 * Product, and Trace Log controller ({@code 01 §3.3 & §3.4}, {@code 02 §4.7–§4.8},
 * Wire Invariants #3, #6, #13, #14, #19).
 */
@RestController
public class UserGroupAdminController {

    private final SqliteRepository sqliteRepository;
    private final ShareService shareService;
    private final RequestAuthResolver authResolver;
    private final CryptoService cryptoService;
    private final SqlflowRuntimeConfig config;
    private final ControllerParamHelper paramHelper;
    private final ObjectMapper objectMapper;

    public UserGroupAdminController(
            SqliteRepository sqliteRepository,
            ShareService shareService,
            RequestAuthResolver authResolver,
            CryptoService cryptoService,
            SqlflowRuntimeConfig config,
            ControllerParamHelper paramHelper,
            ObjectMapper objectMapper) {
        this.sqliteRepository = sqliteRepository;
        this.shareService = shareService;
        this.authResolver = authResolver;
        this.cryptoService = cryptoService;
        this.config = config;
        this.paramHelper = paramHelper;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 1. User Auth, Dual Login/Register, Credentials, & Profile (`01 §3.3`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/user/register", "/user/login", "/sqlflow/user/register", "/sqlflow/user/login"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> registerOrLogin(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = params.get("userId");
        String userName = LineageGenerationService.firstNonBlank(params, "userName", "username", "name");
        String userEmail = LineageGenerationService.firstNonBlank(params, "userEmail", "email");
        String password = params.get("password");
        String source = LineageGenerationService.firstNonBlank(params, "userSource", "source");

        SqliteRepository.RegisterResult res = sqliteRepository.registerOrLogin(
                userId, userName, password, userEmail, source != null ? source : "premise");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("first_login", res.firstLogin());
        out.put("token", res.token());
        out.put("userId", res.user().getUserId());
        out.put("data", res.user());
        return out;
    }

    /**
     * Wire Invariant #3: {@code code} MUST be string {@code "200"} and {@code secretKey} top-level.
     */
    @PostMapping(
            value = {"/user/generateSecretKey", "/sqlflow/user/generateSecretKey"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SecretKeyResponse generateSecretKey(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String secretKey = sqliteRepository.generateSecretKey(userId);
        return SecretKeyResponse.ok(userId, secretKey);
    }

    /**
     * Wire Invariant #3: {@code code} MUST be string {@code "200"} and {@code token} top-level.
     */
    @PostMapping(
            value = {"/user/generateToken", "/sqlflow/user/generateToken"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TokenResponse generateToken(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String secretKey = params.get("secretKey");
        String requestedUserId = params.get("userId");

        UserInfo user = null;
        if (secretKey != null && !secretKey.isBlank()) {
            user = sqliteRepository.findBySecretKey(secretKey.trim());
            if (user == null) {
                return TokenResponse.error("401", "Invalid secretKey.");
            }
            if (requestedUserId != null
                    && !requestedUserId.isBlank()
                    && !requestedUserId.equals(user.getUserId())) {
                return TokenResponse.error("401", "userId does not match secretKey.");
            }
        } else {
            String resolved = authResolver.resolveAndValidateUser(request, requestedUserId, false);
            user = sqliteRepository.findByUserId(resolved);
            if (user == null) {
                return TokenResponse.error("401", "Invalid user.");
            }
        }

        String jwt = cryptoService.issueJwt(
                user.getUserId(),
                user.getUserSource() != null ? user.getUserSource() : "premise",
                config.getUserTokenExpireHours());
        return TokenResponse.ok(user.getUserId(), jwt, user.getUserSource());
    }

    @RequestMapping(
            value = {
                "/user/userInfo",
                "/user/getUserInfo",
                "/user/info",
                "/sqlflow/user/userInfo",
                "/sqlflow/user/getUserInfo"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> getUserInfo(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        UserInfo info = sqliteRepository.findByUserId(userId);
        if (info == null) {
            throw SqlflowBusinessException.invalidUser("Invalid user.");
        }
        return ApiResponse.ok(info);
    }

    @PostMapping(
            value = {"/user/addUser", "/sqlflow/user/addUser"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> addUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        boolean sysAdmin = LineageGenerationService.parseBool(
                LineageGenerationService.firstNonBlank(params, "systemAdmin", "isAdmin"), false)
                || "1".equals(params.get("userRole"));
        UserInfo created = sqliteRepository.addUser(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "userName", "name"),
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                params.get("password"),
                LineageGenerationService.firstNonBlank(params, "expireDate", "endDate"),
                sysAdmin);
        return ApiResponse.ok(created);
    }

    @PostMapping(
            value = {"/user/updateUser", "/sqlflow/user/updateUser"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> updateUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        Boolean sysAdmin = params.containsKey("systemAdmin")
                ? LineageGenerationService.parseBool(params.get("systemAdmin"), false)
                : (params.containsKey("userRole") ? "1".equals(params.get("userRole")) : null);
        UserInfo updated = sqliteRepository.updateUser(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "userName", "name"),
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                params.get("password"),
                LineageGenerationService.firstNonBlank(params, "expireDate", "endDate"),
                sysAdmin);
        return ApiResponse.ok(updated);
    }

    @PostMapping(
            value = {"/user/deleteUser", "/sqlflow/user/deleteUser"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<String> deleteUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        String email = LineageGenerationService.firstNonBlank(params, "userEmail", "email");
        if (email == null || email.isBlank()) {
            String targetUid = LineageGenerationService.firstNonBlank(params, "targetUserId", "user");
            if (targetUid != null && !targetUid.isBlank()) {
                UserInfo target = sqliteRepository.findByUserId(targetUid);
                if (target != null && target.getUserEmail() != null) {
                    email = target.getUserEmail();
                } else {
                    email = targetUid;
                }
            }
        }
        String deletedUid = sqliteRepository.deleteUserByEmail(opUserId, email);
        return ApiResponse.ok(deletedUid);
    }

    @PostMapping(
            value = {"/user/modifyPassword", "/sqlflow/user/modifyPassword"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> modifyPassword(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        sqliteRepository.modifyPassword(
                userId,
                LineageGenerationService.firstNonBlank(params, "oldPassword", "currentPassword"),
                LineageGenerationService.firstNonBlank(params, "newPassword", "password"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/user/resetPassword", "/sqlflow/user/resetPassword"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> resetPassword(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.resetPassword(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                LineageGenerationService.firstNonBlank(params, "newPassword", "password"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/user/unregister", "/sqlflow/user/unregister"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> unregister(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        sqliteRepository.unregister(
                params.get("targetUserId") != null ? params.get("targetUserId") : userId,
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                userId);
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {
                "/user/upgradeUser",
                "/user/upgrade",
                "/user/upgradeByEmail",
                "/sqlflow/user/upgradeUser"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> upgradeUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        String target = LineageGenerationService.firstNonBlank(
                params, "targetUserId", "upgradeUserId", "userEmail", "email", "userId");
        int days = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "renewalDays", "renewalDate", "days", "period"), 30);
        return ApiResponse.ok(sqliteRepository.upgradeUser(opUserId, target, days));
    }

    @RequestMapping(
            value = {
                "/user/listUsersByType",
                "/user/premium/users",
                "/user/pay/users",
                "/sqlflow/user/listUsersByType"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> listUsersByType(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        String defaultType = uri.contains("/pay/") ? "pay" : "premium";
        return ApiResponse.ok(sqliteRepository.listUsersByType(params.getOrDefault("userType", defaultType)));
    }

    @RequestMapping(
            value = {
                "/user/queryUsers",
                "/user/users",
                "/user/list",
                "/sqlflow/user/queryUsers"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> queryUsers(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, Object> query = paramHelper.extractObjectMap(request, jsonBody);
        return ApiResponse.ok(sqliteRepository.queryUsers(query));
    }

    @RequestMapping(
            value = {"/user/infoByEmail", "/user/infoWithSameEmailById"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> getUserInfoByEmailOrId(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String lookup = LineageGenerationService.firstNonBlank(params, "userEmail", "email", "userId");
        String resolvedId = lookup != null ? sqliteRepository.resolveUserId(lookup) : null;
        UserInfo info = resolvedId != null ? sqliteRepository.findByUserId(resolvedId) : null;
        if (info == null) {
            throw SqlflowBusinessException.invalidUser("User not found.");
        }
        return ApiResponse.ok(info);
    }

    @PostMapping(value = {"/user/validate", "/user/userId", "/user/userIdBySecretKey", "/user/jobUser", "/user/sendVerifyEmail"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> userInternalHelpers(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        if (uri.endsWith("/userIdBySecretKey")) {
            UserInfo u = sqliteRepository.findBySecretKey(params.get("secretKey"));
            return u != null ? ApiResponse.ok(u.getUserId()) : ApiResponse.error(401, "Invalid secretKey");
        }
        if (uri.endsWith("/userId")) {
            String resolved = sqliteRepository.resolveUserId(
                    LineageGenerationService.firstNonBlank(params, "user", "userEmail", "userId"));
            return resolved != null ? ApiResponse.ok(resolved) : ApiResponse.error(404, "User not found");
        }
        if (uri.endsWith("/sendVerifyEmail")) {
            return ApiResponse.ok(Boolean.TRUE);
        }
        String uid = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        UserInfo info = sqliteRepository.findByUserId(uid);
        if (uri.endsWith("/jobUser") && info != null) {
            return ApiResponse.ok(Map.of(
                    "userIds", List.of(info.getUserId()),
                    "userEmail", info.getUserEmail() != null ? info.getUserEmail() : "",
                    "group", info.getUserGroup() != null ? info.getUserGroup() : "",
                    "userRole", info.getUserRole() != null ? info.getUserRole() : "1",
                    "userType", info.getUserType() != null ? info.getUserType() : "premium"));
        }
        return ApiResponse.ok(info);
    }

    @PostMapping(
            value = {"/user/applyUserCoupon", "/user/useCoupon", "/sqlflow/user/applyUserCoupon"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> applyUserCoupon(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String code = LineageGenerationService.firstNonBlank(params, "couponCode", "code", "coupon");
        return ApiResponse.ok(sqliteRepository.applyUserCoupon(userId, code));
    }

    // -------------------------------------------------------------------------
    // 2. User Shares Summary & Delete (`01 §3.3.5`)
    // -------------------------------------------------------------------------

    @RequestMapping(
            value = {"/user/displayUserShareSummary", "/sqlflow/user/displayUserShareSummary"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<ShareInfo>> displayUserShareSummary(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        return ApiResponse.ok(shareService.listUserShares(userId));
    }

    @RequestMapping(
            value = {"/user/deleteUserShare", "/sqlflow/user/deleteUserShare"},
            method = {RequestMethod.POST, RequestMethod.DELETE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> deleteUserShare(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String shareId = LineageGenerationService.firstNonBlank(params, "shareId", "id");
        return ApiResponse.ok(shareService.deleteUserShare(userId, shareId));
    }

    // -------------------------------------------------------------------------
    // 3. MD5-Hashed User Settings (`data/user/settings/{MD5(section)}/{MD5(userId)}.json`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/user/saveUserSetting", "/user/settings", "/sqlflow/user/saveUserSetting"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> saveUserSetting(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) throws IOException {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String section = LineageGenerationService.firstNonBlank(params, "section", "key", "type");
        if (section == null || section.isBlank()) {
            section = "default";
        }
        String valueRaw = LineageGenerationService.firstNonBlank(params, "value", "setting", "data");
        Path file = resolveUserSettingFile(section, userId);
        Files.createDirectories(file.getParent());

        Object toWrite = valueRaw != null ? valueRaw : Map.of();
        if (valueRaw != null && (valueRaw.trim().startsWith("{") || valueRaw.trim().startsWith("["))) {
            try {
                toWrite = objectMapper.readValue(valueRaw, Object.class);
            } catch (Exception ignored) {
            }
        } else if (valueRaw == null && jsonBody != null) {
            toWrite = jsonBody;
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), toWrite);
        return ApiResponse.ok(Boolean.TRUE);
    }

    @RequestMapping(
            value = {"/user/getUserSetting", "/sqlflow/user/getUserSetting"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> getUserSetting(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) throws IOException {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String section = LineageGenerationService.firstNonBlank(params, "section", "key", "type");
        if (section == null || section.isBlank()) {
            section = "default";
        }
        Path file = resolveUserSettingFile(section, userId);
        if (!Files.isRegularFile(file)) {
            return ApiResponse.error(404, "user setting not exists!");
        }
        Object val = objectMapper.readValue(file.toFile(), Object.class);
        return ApiResponse.ok(val);
    }

    @GetMapping(value = "/user/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Object> getUserSettingsGetAlias(HttpServletRequest request) throws IOException {
        return getUserSetting(request, null);
    }

    private Path resolveUserSettingFile(String section, String userId) {
        String secHash = CryptoService.md5Hex(section.trim());
        String uidHash = CryptoService.md5Hex(userId.trim());
        return config.getDataDir()
                .resolve("user")
                .resolve("settings")
                .resolve(secHash)
                .resolve(uidHash + ".json");
    }

    // -------------------------------------------------------------------------
    // 4. Recorded User SQL Audit Logs (`record_sql`, Wire Invariant #13)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/user/sqls", "/sqlflow/user/sqls"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> listUserSqls(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        if (!config.isRecordSql()) {
            throw SqlflowBusinessException.recordSqlDisabled();
        }
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String targetUser = resolveTargetSqlUser(params, userId);
        return ApiResponse.ok(scanRecordedSqlFiles(targetUser));
    }

    @PostMapping(
            value = {"/user/sqlStatistic", "/sqlflow/user/sqlStatistic"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> sqlStatistic(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        if (!config.isRecordSql()) {
            throw SqlflowBusinessException.recordSqlDisabled();
        }
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String targetUser = resolveTargetSqlUser(params, userId);
        List<Map<String, Object>> files = scanRecordedSqlFiles(targetUser);
        return ApiResponse.ok(Map.of("total", files.size(), "files", files));
    }

    @PostMapping(
            value = {"/user/previewUserSql", "/sqlflow/user/previewUserSql"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<String> previewUserSql(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) throws IOException {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String targetUser = resolveTargetSqlUser(params, userId);
        String fileName = params.get("fileName");
        Path sqlFile = findRecordedSqlFile(targetUser, fileName);
        if (sqlFile == null) {
            return ApiResponse.error(404, "SQL file not found: " + fileName);
        }
        return ApiResponse.ok(Files.readString(sqlFile, StandardCharsets.UTF_8));
    }

    /**
     * Wire Invariant #13: {@code POST /user/downloadUserSql} accepts query parameters on empty-body POST.
     */
    @RequestMapping(
            value = {"/user/downloadUserSql", "/sqlflow/user/downloadUserSql"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> downloadUserSql(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String userId = authResolver.resolveAndValidateUser(request, params.get("userId"), false);
        String targetUser = resolveTargetSqlUser(params, userId);
        String fileName = params.get("fileName");
        Path sqlFile = findRecordedSqlFile(targetUser, fileName);
        if (sqlFile == null || !Files.isRegularFile(sqlFile)) {
            return ResponseEntity.ok(ApiResponse.error(404, "SQL file not found: " + fileName));
        }
        return DownloadHelper.fileAttachment(sqlFile, sqlFile.getFileName().toString(), MediaType.APPLICATION_OCTET_STREAM);
    }

    private String resolveTargetSqlUser(Map<String, String> params, String fallbackUserId) {
        String userParam = params.get("user");
        if (userParam != null && !userParam.isBlank()) {
            String resolved = sqliteRepository.resolveUserId(userParam);
            return resolved != null ? resolved : userParam;
        }
        return fallbackUserId;
    }

    private List<Map<String, Object>> scanRecordedSqlFiles(String targetUserId) {
        Path userLogDir = config.getBaseSqlLogDir()
                .resolve(URLEncoder.encode(targetUserId, StandardCharsets.UTF_8));
        if (!Files.isDirectory(userLogDir)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(userLogDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path f : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", f.getFileName().toString());
                item.put("size", Files.size(f));
                out.add(item);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private Path findRecordedSqlFile(String targetUserId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String safeName = Path.of(fileName).getFileName().toString();
        Path userLogDir = config.getBaseSqlLogDir()
                .resolve(URLEncoder.encode(targetUserId, StandardCharsets.UTF_8));
        if (!Files.isDirectory(userLogDir)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(userLogDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(safeName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 5. Group Management (`/group/**`, `02 §4.7`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/group/createGroup", "/group/create", "/sqlflow/group/createGroup"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<GroupInfo> createGroup(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        int quote = LineageGenerationService.parseInt(params.get("quote"), 10);
        GroupInfo g = sqliteRepository.createGroup(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupName", "name"),
                quote,
                params.get("createdDate"),
                LineageGenerationService.firstNonBlank(params, "expireDate", "expiredDate"),
                LineageGenerationService.firstNonBlank(params, "teamManagerEmail", "managerEmail"));
        return ApiResponse.ok(g);
    }

    @RequestMapping(
            value = {"/group/getGroup", "/group/groupInfo", "/group/info", "/sqlflow/group/getGroup"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<GroupInfo> getGroup(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        String groupId = LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id");
        return ApiResponse.ok(sqliteRepository.getGroup(opUserId, groupId));
    }

    @RequestMapping(
            value = {"/group/listGroupUsers", "/sqlflow/group/listGroupUsers"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<GroupInfo.GroupMember>> listGroupUsers(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        String groupId = LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id");
        GroupInfo group = sqliteRepository.getGroup(opUserId, groupId);
        return ApiResponse.ok(group != null && group.getUsers() != null ? group.getUsers() : List.of());
    }

    @RequestMapping(
            value = {"/group/listGroups", "/group/list", "/sqlflow/group/listGroups"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<GroupInfo>> listGroups(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.listGroups(opUserId));
    }

    @PostMapping(
            value = {"/group/deleteGroup", "/group/delete", "/sqlflow/group/deleteGroup"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> deleteGroup(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.deleteGroup(
                opUserId, LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/group/setGroupName", "/group/rename", "/sqlflow/group/setGroupName"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<GroupInfo> setGroupName(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.setGroupName(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"),
                LineageGenerationService.firstNonBlank(params, "groupName", "name")));
    }

    @PostMapping(
            value = {
                "/group/upgradeGroup",
                "/group/renewGroup",
                "/group/upgradeGroupByEmail",
                "/sqlflow/group/upgradeGroup"
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<GroupInfo> upgradeGroup(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        Integer quote = params.get("quote") != null ? LineageGenerationService.parseInt(params.get("quote"), 10) : null;
        Integer renewalDays = params.get("renewalDays") != null
                ? LineageGenerationService.parseInt(params.get("renewalDays"), 30)
                : null;
        return ApiResponse.ok(sqliteRepository.upgradeGroup(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"),
                quote,
                params.get("expireDate"),
                renewalDays,
                params.get("groupName")));
    }

    @PostMapping(
            value = {"/group/addGroupUser", "/sqlflow/group/addGroupUser"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> addGroupUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.addGroupUser(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"),
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                params.getOrDefault("userRole", "3"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/group/removeGroupUser", "/sqlflow/group/removeGroupUser"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> removeGroupUser(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.removeGroupUser(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"),
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/group/updateGroupUserRole", "/sqlflow/group/updateGroupUserRole"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> updateGroupUserRole(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.updateGroupUserRole(
                opUserId,
                LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"),
                LineageGenerationService.firstNonBlank(params, "userEmail", "email"),
                params.getOrDefault("userRole", "3"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/group/syncGroupUsers", "/sqlflow/group/syncGroupUsers"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> syncGroupUsers(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.syncGroupUsers(
                opUserId, LineageGenerationService.firstNonBlank(params, "groupId", "userGroup", "id"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    // -------------------------------------------------------------------------
    // 6. Coupons, Orders, Products, & Audit Trace Logs (`02 §4.8`)
    // -------------------------------------------------------------------------

    @PostMapping(
            value = {"/coupon/createCoupon", "/coupon/create", "/sqlflow/coupon/createCoupon"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> createCoupon(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        int period = LineageGenerationService.parseInt(params.get("period"), 30);
        return ApiResponse.ok(sqliteRepository.createCoupon(
                opUserId,
                params.getOrDefault("name", "Promo"),
                params.get("code"),
                params.getOrDefault("expireDate", "2099-12-31"),
                period));
    }

    @PostMapping(
            value = {"/coupon/batchCreateCoupons", "/sqlflow/coupon/batchCreateCoupons"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> batchCreateCoupons(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.batchCreateCoupons(
                opUserId,
                params.getOrDefault("name", "BatchPromo"),
                params.getOrDefault("expireDate", "2099-12-31"),
                LineageGenerationService.parseInt(params.get("period"), 30),
                LineageGenerationService.parseInt(params.get("number"), 5)));
    }

    @RequestMapping(
            value = {"/coupon/getCouponByCode", "/coupon/couponInfo", "/sqlflow/coupon/getCouponByCode"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> getCouponByCode(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.getCouponByCode(
                opUserId, LineageGenerationService.firstNonBlank(params, "code", "couponCode")));
    }

    @RequestMapping(
            value = {"/coupon/listUnusedCoupons", "/coupon/listCoupons", "/sqlflow/coupon/listUnusedCoupons"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> listUnusedCoupons(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.listUnusedCoupons(opUserId));
    }

    @PostMapping(
            value = {"/coupon/deleteCoupon", "/sqlflow/coupon/deleteCoupon"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> deleteCoupon(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        sqliteRepository.deleteCoupon(
                opUserId, LineageGenerationService.firstNonBlank(params, "code", "couponCode"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @RequestMapping(
            value = {"/coupon/getUserCouponInfo", "/coupon/userCouponInfo", "/sqlflow/coupon/getUserCouponInfo"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<Map<String, Object>>> getUserCouponInfo(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        return ApiResponse.ok(sqliteRepository.getUserCouponInfo(
                opUserId, LineageGenerationService.firstNonBlank(params, "userEmail", "email")));
    }

    @RequestMapping(
            value = {"/coupon/queryCoupons", "/coupon/coupons", "/coupon/list", "/sqlflow/coupon/queryCoupons"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> queryCoupons(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return ApiResponse.ok(sqliteRepository.queryCoupons(paramHelper.extractObjectMap(request, jsonBody)));
    }

    @PostMapping(
            value = {"/order/callback", "/order/callback2", "/order/recordOrder", "/sqlflow/order/callback"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> recordOrder(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        sqliteRepository.recordOrder(
                LineageGenerationService.firstNonBlank(params, "orderId", "orderNumber", "reference"),
                LineageGenerationService.firstNonBlank(params, "email", "userEmail"),
                params.get("firstName"),
                params.get("lastName"),
                params.get("company"),
                params.get("productSku"));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/order/upgradeUserByOrder", "/user/upgradeByOrder", "/sqlflow/order/upgradeUserByOrder"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserInfo> upgradeUserByOrder(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        Map<String, String> params = paramHelper.extractParams(request, jsonBody);
        String opUserId = authResolver.resolveAndValidateUser(
                request, LineageGenerationService.firstNonBlank(params, "operatorUserId", "userId"), false);
        int days = LineageGenerationService.parseInt(
                LineageGenerationService.firstNonBlank(params, "renewalDays", "renewalDate"), 30);
        return ApiResponse.ok(sqliteRepository.upgradeUserByOrder(
                opUserId,
                params.get("userEmail"),
                LineageGenerationService.firstNonBlank(params, "orderEmail", "userEmail"),
                LineageGenerationService.firstNonBlank(params, "orderNumber", "orderId"),
                days));
    }

    /**
     * Wire Invariant #14: Top-level {@code products} array on {@code /product/products}.
     */
    @RequestMapping(
            value = {
                "/product/products",
                "/sqlflow/product/products",
                "/sqlflow/user/product/list"
            },
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ProductListEnvelope listProducts() {
        return ProductListEnvelope.ok(sqliteRepository.listProducts());
    }

    @PostMapping(
            value = {"/log/trace", "/trace/log", "/sqlflow/log/trace"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> insertTraceLog(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        sqliteRepository.insertTraceLog(paramHelper.extractObjectMap(request, jsonBody));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @PostMapping(
            value = {"/log/userRequestStart", "/log/traceUserRequestStart", "/sqlflow/log/userRequestStart"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Long> userRequestStart(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return ApiResponse.ok(sqliteRepository.insertUserRequestStart(paramHelper.extractObjectMap(request, jsonBody)));
    }

    @PostMapping(
            value = {"/log/userRequestEnd", "/log/traceUserRequestEnd", "/sqlflow/log/userRequestEnd"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Boolean> userRequestEnd(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        sqliteRepository.updateUserRequestEnd(paramHelper.extractObjectMap(request, jsonBody));
        return ApiResponse.ok(Boolean.TRUE);
    }

    @RequestMapping(
            value = {"/log/queryUserRequests", "/user/userRequests", "/sqlflow/log/queryUserRequests"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> queryUserRequests(
            HttpServletRequest request,
            @OptionalJsonBody Map<String, Object> jsonBody) {
        return ApiResponse.ok(sqliteRepository.queryUserRequests(paramHelper.extractObjectMap(request, jsonBody)));
    }
}
