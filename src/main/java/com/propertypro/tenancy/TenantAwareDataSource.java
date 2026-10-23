package com.propertypro.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Wraps the connection pool. Every time a connection is handed out it is told
 * which organisation and user it is working for, so PostgreSQL Row-Level
 * Security (V7 migration) can enforce isolation.
 *
 * <p>The settings are written on EVERY checkout (empty strings when there is no
 * context), so a pooled connection never carries the previous request's
 * organisation. No organisation set = the database returns no tenant rows.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_CONTEXT_SQL = """
            SELECT set_config('app.current_org',  ?, false),
                   set_config('app.current_user', ?, false),
                   set_config('app.bypass_rls',   ?, false)
            """;

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyContext(super.getConnection(username, password));
    }

    private Connection applyContext(Connection connection) throws SQLException {
        TenantContext.State state = TenantContext.snapshot();
        try (PreparedStatement ps = connection.prepareStatement(SET_CONTEXT_SQL)) {
            ps.setString(1, asText(state.organizationId()));
            ps.setString(2, asText(state.userId()));
            ps.setString(3, state.system() ? "on" : "off");
            ps.execute();
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private static String asText(UUID id) {
        return id == null ? "" : id.toString();
    }
}
