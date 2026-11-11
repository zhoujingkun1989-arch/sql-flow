package io.sqlflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.vendor.CaseCompareMode;
import io.sqlflow.contracts.vendor.CaseFoldMode;
import io.sqlflow.contracts.vendor.CustomizeConfig;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.contracts.vendor.IdentifierProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class VendorAndIdentifierProfileTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void all48VendorsMatchExactOrdinalOrderAndImplementationFlags() {
        EDbVendor[] vendors = EDbVendor.values();
        assertThat(vendors).hasSize(48);

        List<String> expectedNames = List.of(
                "dbvaccess",
                "dbvansi",
                "dbvathena",
                "dbvazuresql",
                "dbvbigquery",
                "dbvclickhouse",
                "dbvcouchbase",
                "dbvdax",
                "dbvdb2",
                "dbvdoris",
                "dbvduckdb",
                "dbvexasol",
                "dbvfirebird",
                "dbvflink",
                "dbvgeneric",
                "dbvgreenplum",
                "dbvhana",
                "dbvhive",
                "dbvimpala",
                "dbvinformix",
                "dbvmdx",
                "dbvmysql",
                "dbvmssql",
                "dbvnetezza",
                "dbvodbc",
                "dbvopenedge",
                "dbvoracle",
                "dbvpostgresql",
                "dbvpowerquery",
                "dbvpresto",
                "dbvredshift",
                "dbvsnowflake",
                "dbvsoql",
                "dbvsparksql",
                "dbvsqlite",
                "dbvstarrocks",
                "dbvsybase",
                "dbvteradata",
                "dbvtrino",
                "dbvvertica",
                "dbvdatabricks",
                "dbvgaussdb",
                "dbvedb",
                "dbvdameng",
                "dbvoceanbase",
                "dbvsybasease",
                "dbvsqlanywhere",
                "dbvsybaseiq");

        for (int i = 0; i < 48; i++) {
            assertThat(vendors[i].ordinal()).isEqualTo(i);
            assertThat(vendors[i].name()).isEqualTo(expectedNames.get(i));
            assertThat(vendors[i].wireName()).isEqualTo(expectedNames.get(i));
        }

        Set<EDbVendor> unimplemented = Arrays.stream(vendors)
                .filter(v -> !v.isImplemented())
                .collect(Collectors.toSet());
        assertThat(unimplemented).containsExactlyInAnyOrder(
                EDbVendor.dbvaccess,
                EDbVendor.dbvansi,
                EDbVendor.dbvdax,
                EDbVendor.dbvexasol,
                EDbVendor.dbvfirebird,
                EDbVendor.dbvgeneric);

        long implementedCount = Arrays.stream(vendors).filter(EDbVendor::isImplemented).count();
        assertThat(implementedCount).isEqualTo(42L);
    }

    @Test
    void vendorCaseInsensitiveLookupAndJsonRoundTripSupportBothPrefixedAndShortNames() throws Exception {
        assertThat(EDbVendor.fromString("dbvoracle")).isEqualTo(EDbVendor.dbvoracle);
        assertThat(EDbVendor.fromString("oracle")).isEqualTo(EDbVendor.dbvoracle);
        assertThat(EDbVendor.fromString("ORACLE")).isEqualTo(EDbVendor.dbvoracle);
        assertThat(EDbVendor.fromString("postgresql")).isEqualTo(EDbVendor.dbvpostgresql);
        assertThat(EDbVendor.fromString("postgres")).isEqualTo(EDbVendor.dbvpostgresql);
        assertThat(EDbVendor.fromString("sqlserver")).isEqualTo(EDbVendor.dbvmssql);
        assertThat(EDbVendor.fromString("tsql")).isEqualTo(EDbVendor.dbvmssql);
        assertThat(EDbVendor.fromString("bigquery")).isEqualTo(EDbVendor.dbvbigquery);
        assertThat(EDbVendor.fromString("databricks")).isEqualTo(EDbVendor.dbvdatabricks);

        String serialized = mapper.writeValueAsString(EDbVendor.dbvsnowflake);
        assertThat(serialized).isEqualTo("\"dbvsnowflake\"");
        assertThat(mapper.readValue("\"snowflake\"", EDbVendor.class)).isEqualTo(EDbVendor.dbvsnowflake);
        assertThat(mapper.readValue("\"DBVSNOWFLAKE\"", EDbVendor.class)).isEqualTo(EDbVendor.dbvsnowflake);
    }

    @Test
    void identifierProfilesMatchAll48VendorDialectRules() {
        for (EDbVendor vendor : EDbVendor.values()) {
            IdentifierProfile profile = IdentifierProfile.forVendor(vendor);
            assertThat(profile).isNotNull();
            assertThat(profile.vendor()).isEqualTo(vendor);
        }

        // Oracle & Dameng: unquoted UPPER, quoted SENSITIVE, catalog=false, schema=true
        IdentifierProfile oracle = IdentifierProfile.forVendor(EDbVendor.dbvoracle);
        assertThat(oracle.metadataSupportsCatalogs()).isFalse();
        assertThat(oracle.metadataSupportsSchemas()).isTrue();
        assertThat(oracle.normalizeIdentifier(DataObjectType.TABLE, "emp")).isEqualTo("EMP");
        assertThat(oracle.normalizeIdentifier(DataObjectType.TABLE, "\"emp\"")).isEqualTo("emp");
        assertThat(oracle.ruleFor(DataObjectType.TABLE).matches("emp", false, "EMP", false, 0)).isTrue();
        assertThat(oracle.ruleFor(DataObjectType.TABLE).matches("emp", true, "EMP", false, 0)).isFalse();

        IdentifierProfile dameng = IdentifierProfile.forVendor(EDbVendor.dbvdameng);
        assertThat(dameng.metadataSupportsCatalogs()).isFalse();
        assertThat(dameng.metadataSupportsSchemas()).isTrue();

        // PostgreSQL: unquoted LOWER + 63 UTF-8 byte truncation
        IdentifierProfile pg = IdentifierProfile.forVendor(EDbVendor.dbvpostgresql);
        assertThat(pg.maxUtf8Bytes()).isEqualTo(63);
        String longIdent = "A".repeat(80);
        String normalizedPg = pg.normalizeIdentifier(DataObjectType.TABLE, longIdent);
        assertThat(normalizedPg).isEqualTo("a".repeat(63));

        // Redshift: quoted identifiers also fold LOWER by default
        IdentifierProfile redshift = IdentifierProfile.forVendor(EDbVendor.dbvredshift);
        assertThat(redshift.defaultRule().quotedFold()).isEqualTo(CaseFoldMode.LOWER);
        assertThat(redshift.defaultRule().quotedCompare()).isEqualTo(CaseCompareMode.INSENSITIVE);
        assertThat(redshift.normalizeIdentifier(DataObjectType.TABLE, "\"MyTable\"")).isEqualTo("mytable");

        // BigQuery: table case-sensitive, column case-insensitive
        IdentifierProfile bigQuery = IdentifierProfile.forVendor(EDbVendor.dbvbigquery);
        assertThat(bigQuery.ruleFor(DataObjectType.TABLE).unquotedCompare()).isEqualTo(CaseCompareMode.SENSITIVE);
        assertThat(bigQuery.ruleFor(DataObjectType.COLUMN).unquotedCompare()).isEqualTo(CaseCompareMode.INSENSITIVE);
        assertThat(bigQuery.ruleFor(DataObjectType.TABLE).matches("Orders", false, "orders", false, 0)).isFalse();
        assertThat(bigQuery.ruleFor(DataObjectType.COLUMN).matches("OrderId", false, "orderid", false, 0)).isTrue();

        // MySQL, Hive, Impala, Teradata: metadataSupportsSchemas == false
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvmysql).metadataSupportsSchemas()).isFalse();
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvhive).metadataSupportsSchemas()).isFalse();
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvimpala).metadataSupportsSchemas()).isFalse();
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvteradata).metadataSupportsSchemas()).isFalse();

        // Sybase family: aliasOccludesColumn == true
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvsybase).aliasOccludesColumn()).isTrue();
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvsqlanywhere).aliasOccludesColumn()).isTrue();
        assertThat(IdentifierProfile.forVendor(EDbVendor.dbvsybaseiq).aliasOccludesColumn()).isTrue();

        // Runtime CustomizeConfig.CaseSensitiveRules override
        CustomizeConfig.CaseSensitiveRules rules = new CustomizeConfig.CaseSensitiveRules();
        rules.setDefaults(List.of(new CustomizeConfig.VendorCaseSensitive("oracle", Boolean.FALSE)));
        IdentifierProfile customizedOracle = oracle.withCustomizeOverrides(rules);
        assertThat(customizedOracle.isQuotedToken("\"my_col\"")).isTrue();
        assertThat(customizedOracle.defaultRule().quotedCompare()).isEqualTo(CaseCompareMode.INSENSITIVE);
    }
}
