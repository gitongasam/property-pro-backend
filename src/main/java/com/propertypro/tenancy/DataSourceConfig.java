package com.propertypro.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * The app's DataSource = the Hikari pool wrapped in {@link TenantAwareDataSource}.
 * Flyway does not use it: it connects separately as the owner account
 * (spring.flyway.url / user in application.yml).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource hikariPool(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikariPool) {
        return new TenantAwareDataSource(hikariPool);
    }
}
