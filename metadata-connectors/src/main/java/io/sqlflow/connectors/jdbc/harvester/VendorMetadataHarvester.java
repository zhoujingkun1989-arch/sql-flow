package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Strategy contract for harvesting live catalog metadata from a vendor-specific JDBC connection ({@code OBL-075, OBL-076}).
 */
public interface VendorMetadataHarvester {

    void harvest(
            Connection connection,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int queryTimeoutSeconds) throws SQLException;
}
