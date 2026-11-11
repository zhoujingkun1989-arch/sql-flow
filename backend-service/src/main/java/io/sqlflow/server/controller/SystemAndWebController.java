package io.sqlflow.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.LicenseInfo;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.contracts.domain.VersionEnvelope;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.CryptoService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Root SPA bootstrap, runtime {@code config.private.json}, unwrapped {@code /version}
 * (Wire Invariant #2), open config-driven {@code /license} ({@code OBL-018}), and runtime
 * 1024-bit RSA public key endpoints ({@code OBL-022}).
 */
@RestController
public class SystemAndWebController {

    private final SqlflowRuntimeConfig config;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public SystemAndWebController(
            SqlflowRuntimeConfig config,
            CryptoService cryptoService,
            ObjectMapper objectMapper) {
        this.config = config;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> serveSpaIndex() throws IOException {
        Path externalIndex = config.getStaticDir().resolve("index.html");
        if (Files.isRegularFile(externalIndex)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(Files.readString(externalIndex, StandardCharsets.UTF_8));
        }
        ClassPathResource cp = new ClassPathResource("static/index.html");
        try (InputStream in = cp.getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @GetMapping(value = {"/widget", "/widget/"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> serveWidgetRoot() throws IOException {
        Path externalWidget = config.getWidgetDir().resolve("index.html");
        if (Files.isRegularFile(externalWidget)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(Files.readString(externalWidget, StandardCharsets.UTF_8));
        }
        ClassPathResource cp = new ClassPathResource("widget/index.html");
        try (InputStream in = cp.getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Wire Invariant #18: serves {@code config.private.json} with built-in guest {@code gudu|0123456789}
     * and runtime 1024-bit RSA public key.
     */
    @GetMapping(value = "/config.private.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> serveConfigPrivateJson() throws IOException {
        Path externalCfg = config.getStaticDir().resolve("config.private.json");
        if (Files.isRegularFile(externalCfg)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fromDisk = objectMapper.readValue(externalCfg.toFile(), Map.class);
            fromDisk.putIfAbsent("userId", UserInfo.GUEST_USER_ID);
            fromDisk.putIfAbsent("rsaPublicKey", cryptoService.getRsaPublicKeyBase64());
            return ResponseEntity.ok(fromDisk);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Product", "sqlflow");
        out.put("ApiPrefix", "/api/gspLive_backend");
        out.put("Authorization", Map.of("base64token", ""));
        out.put("userId", UserInfo.GUEST_USER_ID);
        out.put("trialVersion", false);
        out.put("homepage", config.getRaw("homepage", "editor"));
        out.put("rsaPublicKey", cryptoService.getRsaPublicKeyBase64());
        return ResponseEntity.ok(out);
    }

    /**
     * Wire Invariant #2: Unwrapped {@link VersionEnvelope} on {@code GET|POST /version} and
     * {@code /sqlflow/version}.
     */
    @RequestMapping(
            value = {"/version", "/sqlflow/version"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionEnvelope getVersion() {
        VersionEnvelope env = VersionEnvelope.defaultCleanRoom(
                config.getEngineVersion(),
                config.getBackendVersion(),
                config.getReleaseDate());
        VersionEnvelope.VersionDetails details = env.getVersion();
        details.setUi(new VersionEnvelope.ComponentVersion(config.getUiVersion(), config.getReleaseDate()));

        Map<String, Object> support = new LinkedHashMap<>();
        support.put("password.security.validate", config.isPasswordSecurityValidate());
        support.put("regular.job", config.isRegularJobEnabled());
        if (config.isIgnoreUserToken()) {
            support.put("login.disable", Boolean.TRUE);
            support.put("login.userId", config.getAnonymousUserId());
        }
        details.setSupport(support);

        LicenseInfo lic = resolveLicenseInfo();
        details.setCustomerCompanyName(lic.getCompanyName());
        details.setCustomerContactName(lic.getContactName());
        details.setCustomerContactEmail(lic.getContactEmail());
        details.setLicenseExpired(lic.getExpiredDate());
        details.setLicenseMaxUsers(lic.getMaxUsers());
        details.setMachineCode(lic.getMachineCode() != null ? lic.getMachineCode() : "OPEN-CLEANROOM");
        return env;
    }

    @RequestMapping(
            value = {"/license", "/sqlflow/license"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> getLicense() {
        return ApiResponse.ok(resolveLicenseInfo().toDisplayMap());
    }

    @PostMapping(
            value = {"/uploadLicense", "/sqlflow/uploadLicense"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> uploadLicense(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "licenseFile", required = false) MultipartFile licenseFile,
            @RequestParam(value = "content", required = false) String inlineContent) throws IOException {
        MultipartFile chosen = file != null ? file : licenseFile;
        Path target = config.getConfDir().resolve("gudu_sqlflow_license.txt");
        Files.createDirectories(target.getParent());
        if (chosen != null && !chosen.isEmpty()) {
            Files.write(target, chosen.getBytes());
        } else if (inlineContent != null && !inlineContent.isBlank()) {
            Files.writeString(target, inlineContent, StandardCharsets.UTF_8);
        }
        return ApiResponse.ok(resolveLicenseInfo().toDisplayMap());
    }

    @RequestMapping(
            value = {"/rsa/publicKey", "/user/publicKey", "/sqlflow/rsa/publicKey"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> getRsaPublicKey() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("publicKey", cryptoService.getRsaPublicKeyBase64());
        data.put("pem", cryptoService.getRsaPublicKeyPem());
        return ApiResponse.ok(data);
    }

    private LicenseInfo resolveLicenseInfo() {
        LicenseInfo info = LicenseInfo.openCleanRoomDefault();
        info.setProductVersion(config.getBackendVersion());
        info.setCompanyName(config.getRaw("license.company_name", info.getCompanyName()));
        info.setContactName(config.getRaw("license.contact_name", info.getContactName()));
        info.setContactEmail(config.getRaw("license.contact_email", info.getContactEmail()));
        info.setExpiredDate(config.getRaw("license.expired_date", info.getExpiredDate()));

        Path licFile = config.getConfDir().resolve("gudu_sqlflow_license.txt");
        if (Files.isRegularFile(licFile)) {
            try {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(licFile, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                if (props.containsKey("CustomerCompanyName")) {
                    info.setCompanyName(props.getProperty("CustomerCompanyName"));
                }
                if (props.containsKey("CustomerContactName")) {
                    info.setContactName(props.getProperty("CustomerContactName"));
                }
                if (props.containsKey("CustomerContactEmail")) {
                    info.setContactEmail(props.getProperty("CustomerContactEmail"));
                }
                if (props.containsKey("ProductLicense")) {
                    info.setProductLicense(props.getProperty("ProductLicense"));
                }
            } catch (IOException ignored) {
            }
        }
        return info;
    }
}
