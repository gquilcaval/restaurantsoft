package com.restos.bootstrap;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Crea datos de demostración solo cuando la base está vacía para facilitar el primer arranque local. */
@Component
public class DataInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean demoDataEnabled;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;

    public DataInitializer(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            @Value("${restos.demo-data-enabled:true}") boolean demoDataEnabled,
            @Value("${restos.security.bootstrap-admin-email:}") String bootstrapAdminEmail,
            @Value("${restos.security.bootstrap-admin-password:}") String bootstrapAdminPassword) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.demoDataEnabled = demoDataEnabled;
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var users = jdbc.sql("SELECT count(*) FROM app_users").query(Integer.class).single();
        if (users > 0) return;

        if (!demoDataEnabled) {
            createProductionPlatformAdmin();
            return;
        }

        var organizationId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        var adminId = UUID.randomUUID();
        // La demostración no debe quedar bloqueada por el control de suscripción.
        jdbc.sql("INSERT INTO organizations(id,name,slug,paid_until,max_branches) VALUES (:id,'RestOS Demo','restos-demo',CURRENT_DATE + 365,3)")
                .param("id", organizationId).update();
        jdbc.sql("INSERT INTO branches(id,organization_id,name,address) VALUES (:id,:org,'Sucursal Principal','Lima, Perú')")
                .param("id", branchId).param("org", organizationId).update();
        jdbc.sql("""
                INSERT INTO app_users(id,full_name,email,password_hash,role)
                VALUES (:id,'Propietario RestOS','platform@restos.local',:password,'PLATFORM_ADMIN')
                """).param("id", UUID.randomUUID())
                .param("password", passwordEncoder.encode("RestOS123!")).update();
        insertUser(adminId, organizationId, branchId, "Administrador RestOS", "admin@restos.local", "ORGANIZATION_ADMIN");
        insertUser(UUID.randomUUID(), organizationId, branchId, "Mesero Demo", "mesero@restos.local", "WAITER");
        insertUser(UUID.randomUUID(), organizationId, branchId, "Cocina Demo", "cocina@restos.local", "KITCHEN");

        for (int number = 1; number <= 8; number++) {
            jdbc.sql("INSERT INTO dining_tables(organization_id,branch_id,label,capacity) VALUES (:org,:branch,:label,4)")
                    .param("org", organizationId).param("branch", branchId)
                    .param("label", String.format("Mesa %02d", number)).update();
        }
        var categoryId = UUID.randomUUID();
        jdbc.sql("INSERT INTO menu_categories(id,organization_id,name,display_order) VALUES (:id,:org,'Platos principales',1)")
                .param("id", categoryId).param("org", organizationId).update();
        insertItem(organizationId, categoryId, "Lomo saltado", "Carne salteada, papas fritas y arroz", new BigDecimal("35.00"));
        insertItem(organizationId, categoryId, "Ceviche", "Pescado fresco, limón, cebolla y ají", new BigDecimal("38.00"));
        insertItem(organizationId, categoryId, "Ají de gallina", "Pollo en crema de ají amarillo con arroz", new BigDecimal("32.00"));
    }

    /** En una instalación nueva de producción crea solo al propietario de plataforma configurado por entorno. */
    private void createProductionPlatformAdmin() {
        if (bootstrapAdminEmail.isBlank() || bootstrapAdminPassword.length() < 12) {
            throw new IllegalStateException(
                    "PLATFORM_ADMIN_EMAIL y PLATFORM_ADMIN_PASSWORD (mínimo 12 caracteres) son obligatorios sin datos demo");
        }
        jdbc.sql("""
                INSERT INTO app_users(full_name,email,password_hash,role)
                VALUES ('Administrador de plataforma',:email,:password,'PLATFORM_ADMIN')
                """).param("email", bootstrapAdminEmail.trim().toLowerCase())
                .param("password", passwordEncoder.encode(bootstrapAdminPassword)).update();
    }

    private void insertUser(UUID id, UUID organizationId, UUID branchId, String name, String email, String role) {
        jdbc.sql("""
                INSERT INTO app_users(id,organization_id,branch_id,full_name,email,password_hash,role)
                VALUES (:id,:org,:branch,:name,:email,:password,:role)
                """).param("id", id).param("org", organizationId).param("branch", branchId)
                .param("name", name).param("email", email)
                .param("password", passwordEncoder.encode("RestOS123!"))
                .param("role", role).update();
    }

    private void insertItem(UUID organizationId, UUID categoryId, String name, String description, BigDecimal price) {
        jdbc.sql("""
                INSERT INTO menu_items(organization_id,category_id,name,description,price)
                VALUES (:org,:category,:name,:description,:price)
                """).param("org", organizationId).param("category", categoryId).param("name", name)
                .param("description", description).param("price", price).update();
    }
}
