package com.propertypro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Sign-in is JWT only (AuthService + JwtAuthenticationFilter), so Spring Boot's
 * default in-memory "user" with a generated password is switched off.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class PropertyProApplication {

    public static void main(String[] args) {
        SpringApplication.run(PropertyProApplication.class, args);
    }
}
