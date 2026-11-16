package com.back.coffeeprod.domain.oauth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("oauth-infra")
class OAuthMigrationTest {
    @Test void v20PreservesExistingMemberAndConstrainsSocialIdentities() throws Exception {
        try(var db=new GenericContainer<>("postgres:16-alpine").withEnv("POSTGRES_PASSWORD","migration-synthetic-password").withExposedPorts(5432)) {
            db.start();String url="jdbc:postgresql://"+db.getHost()+":"+db.getMappedPort(5432)+"/postgres";
            // PostgreSQL이 실제 연결을 수락할 때까지 준비 상태를 확인한다.
            try(var wait=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
                long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
                while(true) {try(var c=DriverManager.getConnection(url,"postgres","migration-synthetic-password")){break;}catch(SQLException e){if(System.nanoTime()>deadline)throw e;Thread.sleep(100);}}
            }
            Flyway.configure().dataSource(url,"postgres","migration-synthetic-password").locations("classpath:db/migration").target("19").load().migrate();
            try(var c=DriverManager.getConnection(url,"postgres","migration-synthetic-password");var sql=c.createStatement()) {
                sql.executeUpdate("INSERT INTO member(email,password,name,nickname,role,grade,status) VALUES ('preserved@example.test','existing-password-hash','기존회원','기존닉네임','USER','BRONZE','ACTIVE')");
            }
            Flyway.configure().dataSource(url,"postgres","migration-synthetic-password").locations("classpath:db/migration").load().migrate();
            try(var c=DriverManager.getConnection(url,"postgres","migration-synthetic-password");var sql=c.createStatement()) {
                try(var row=sql.executeQuery("SELECT password FROM member WHERE email='preserved@example.test'")){assertTrue(row.next());assertEquals("existing-password-hash",row.getString(1));}
                sql.executeUpdate("INSERT INTO member(email,password,name,nickname,role,grade,status) VALUES ('social@example.test',NULL,'소셜회원','소셜닉네임','USER','BRONZE','ACTIVE')");
                sql.executeUpdate("INSERT INTO social_account(member_id,provider,subject_hash) SELECT member_id,'google',repeat('a',64) FROM member WHERE email='social@example.test'");
                assertThrows(SQLException.class,()->sql.executeUpdate("INSERT INTO social_account(member_id,provider,subject_hash) SELECT member_id,'google',repeat('a',64) FROM member WHERE email='preserved@example.test'"));
                assertThrows(SQLException.class,()->sql.executeUpdate("INSERT INTO social_account(member_id,provider,subject_hash) SELECT member_id,'google',repeat('b',64) FROM member WHERE email='social@example.test'"));
                try(var row=sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version='20'")){assertTrue(row.next());assertEquals(1,row.getInt(1));}
            }
        }
    }
}
