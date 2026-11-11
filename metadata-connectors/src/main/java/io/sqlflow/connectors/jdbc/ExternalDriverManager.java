package io.sqlflow.connectors.jdbc;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Pluggable JDBC driver resolver supporting both classpath Maven drivers and external proprietary
 * vendor {@code .jar} archives dropped into {@code lib/drivers/} (or custom driver directories)
 * via {@link URLClassLoader} + {@link DriverShim}.
 */
public final class ExternalDriverManager {

    public static final String DRIVERS_DIR_PROP = "sqlflow.drivers.dir";
    public static final String DRIVERS_DIR_ENV = "SQLFLOW_DRIVERS_DIR";

    private final List<Path> driverDirectories;
    private final List<Driver> externalDrivers = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> activeClassLoaders = new CopyOnWriteArrayList<>();
    private volatile boolean initialized;

    public ExternalDriverManager() {
        this(defaultDriverDirectories());
    }

    public ExternalDriverManager(List<Path> driverDirectories) {
        this.driverDirectories = driverDirectories == null
                ? List.of()
                : List.copyOf(driverDirectories);
    }

    private static List<Path> defaultDriverDirectories() {
        Set<Path> dirs = new LinkedHashSet<>();
        String prop = System.getProperty(DRIVERS_DIR_PROP);
        if (prop != null && !prop.isBlank()) {
            for (String p : prop.split(java.io.File.pathSeparator)) {
                if (!p.isBlank()) {
                    dirs.add(Paths.get(p.trim()));
                }
            }
        }
        String env = System.getenv(DRIVERS_DIR_ENV);
        if (env != null && !env.isBlank()) {
            for (String p : env.split(java.io.File.pathSeparator)) {
                if (!p.isBlank()) {
                    dirs.add(Paths.get(p.trim()));
                }
            }
        }
        dirs.add(Paths.get("lib", "drivers"));
        dirs.add(Paths.get("drivers"));
        return new ArrayList<>(dirs);
    }

    /**
     * Scans configured driver directories for {@code *.jar} files and loads any discovered {@link Driver}
     * implementations via {@link URLClassLoader}. Safe to call repeatedly.
     */
    public synchronized void ensureExternalDriversLoaded(List<String> explicitDriverClassNames) {
        if (!initialized) {
            initialized = true;
            loadJarsFromDirectories(driverDirectories);
        }
        if (explicitDriverClassNames != null) {
            for (String className : explicitDriverClassNames) {
                if (tryLoadDriverClass(className)) {
                    break;
                }
            }
        }
    }

    /**
     * Explicitly loads all {@code *.jar} files from an additional directory (useful for runtime plugin reload or tests).
     */
    public synchronized int loadDriverDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        return loadJarsFromDirectories(List.of(dir));
    }

    /**
     * Explicitly loads a single {@code .jar} file and optionally instantiates {@code explicitDriverClassName}.
     */
    public synchronized Driver loadDriverJar(Path jarFile, String explicitDriverClassName) throws IOException {
        if (jarFile == null || !Files.isRegularFile(jarFile)) {
            throw new IOException("Driver JAR does not exist: " + jarFile);
        }
        URL jarUrl = jarFile.toUri().toURL();
        URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, Thread.currentThread().getContextClassLoader());
        activeClassLoaders.add(loader);
        Driver firstRegistered = null;
        ServiceLoader<Driver> serviceLoader = ServiceLoader.load(Driver.class, loader);
        for (Driver d : serviceLoader) {
            Driver shim = registerShim(d);
            if (firstRegistered == null) {
                firstRegistered = shim;
            }
        }
        if (explicitDriverClassName != null && !explicitDriverClassName.isBlank()) {
            Driver explicit = instantiateDriver(explicitDriverClassName.trim(), loader);
            if (explicit != null) {
                Driver shim = registerShim(explicit);
                if (firstRegistered == null) {
                    firstRegistered = shim;
                }
            }
        }
        return firstRegistered;
    }

    /**
     * Opens a JDBC {@link Connection} using either classpath {@link DriverManager} drivers or externally loaded
     * {@code lib/drivers/*.jar} drivers, throwing a clear diagnostic {@link SQLException} if no driver is installed
     * or if the target host/credentials fail.
     */
    public Connection openConnection(
            String jdbcUrl,
            Properties properties,
            List<String> candidateDriverClasses,
            int loginTimeoutSeconds) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new SQLException("JDBC URL must not be blank");
        }
        ensureExternalDriversLoaded(candidateDriverClasses);

        if (loginTimeoutSeconds > 0) {
            DriverManager.setLoginTimeout(loginTimeoutSeconds);
        }

        // 1. Check externally loaded drivers first (in case user supplied a specific vendor driver override)
        for (Driver extDriver : externalDrivers) {
            if (safeAcceptsUrl(extDriver, jdbcUrl)) {
                Connection conn = extDriver.connect(jdbcUrl, properties);
                if (conn != null) {
                    return conn;
                }
            }
        }

        // 2. Check DriverManager registered drivers
        Driver matchingClasspathDriver = findRegisteredDriver(jdbcUrl);
        if (matchingClasspathDriver == null) {
            String expectedDrivers = (candidateDriverClasses == null || candidateDriverClasses.isEmpty())
                    ? "unknown"
                    : String.join(", ", candidateDriverClasses);
            throw new SQLException(
                    "No suitable JDBC driver found for URL '" + maskUrlCredentials(jdbcUrl)
                            + "'. Expected driver class [" + expectedDrivers
                            + "] on classpath or in lib/drivers/*.jar",
                    "08001");
        }

        return DriverManager.getConnection(jdbcUrl, properties);
    }

    public List<Driver> getExternalDrivers() {
        return Collections.unmodifiableList(externalDrivers);
    }

    private int loadJarsFromDirectories(List<Path> dirs) {
        List<URL> jarUrls = new ArrayList<>();
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
                for (Path jar : stream) {
                    if (Files.isRegularFile(jar)) {
                        jarUrls.add(jar.toUri().toURL());
                    }
                }
            } catch (IOException ignored) {
                // Non-fatal if driver directory is unreadable
            }
        }
        if (jarUrls.isEmpty()) {
            return 0;
        }
        URLClassLoader loader = new URLClassLoader(
                jarUrls.toArray(URL[]::new),
                ExternalDriverManager.class.getClassLoader());
        activeClassLoaders.add(loader);
        int count = 0;
        ServiceLoader<Driver> discovered = ServiceLoader.load(Driver.class, loader);
        for (Driver d : discovered) {
            registerShim(d);
            count++;
        }
        return count;
    }

    private boolean tryLoadDriverClass(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        try {
            Class.forName(className, true, ExternalDriverManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Try external classloaders next
        }
        for (URLClassLoader loader : activeClassLoaders) {
            Driver instantiated = instantiateDriver(className, loader);
            if (instantiated != null) {
                registerShim(instantiated);
                return true;
            }
        }
        return false;
    }

    private Driver instantiateDriver(String className, ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            if (Driver.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Constructor<? extends Driver> ctor = (Constructor<? extends Driver>) clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Ignore unavailable optional driver class
        }
        return null;
    }

    private Driver registerShim(Driver delegate) {
        for (Driver existing : externalDrivers) {
            if (existing instanceof DriverShim shim
                    && shim.delegate.getClass().getName().equals(delegate.getClass().getName())
                    && shim.delegate.getClass().getClassLoader() == delegate.getClass().getClassLoader()) {
                return existing;
            }
        }
        DriverShim shim = new DriverShim(delegate);
        externalDrivers.add(shim);
        try {
            DriverManager.registerDriver(shim);
        } catch (SQLException ignored) {
            // Direct lookup in externalDrivers still works even if DriverManager rejects registration
        }
        return shim;
    }

    private Driver findRegisteredDriver(String jdbcUrl) {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver d = drivers.nextElement();
            if (safeAcceptsUrl(d, jdbcUrl)) {
                return d;
            }
        }
        return null;
    }

    private static boolean safeAcceptsUrl(Driver driver, String url) {
        try {
            return driver.acceptsURL(url);
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    static String maskUrlCredentials(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password|pwd|secret|token)=([^;&?]+)", "$1=***");
    }

    /**
     * Delegation wrapper so {@link DriverManager} accepts {@link Driver} instances loaded via child {@link URLClassLoader}.
     */
    static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
