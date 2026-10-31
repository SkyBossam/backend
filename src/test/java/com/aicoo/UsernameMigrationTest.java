package com.aicoo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UsernameMigrationTest {
    @Test void existingAccountsGainUniqueIdsWithoutReplacingTheirIdentityOrRecords()throws Exception {
        try(var pg=LocalPostgres.builder().start()) {
            String url=pg.getJdbcUrl("postgres","postgres");
            Flyway.configure().dataSource(url,"postgres","").target("7").load().migrate();
            try(var c=DriverManager.getConnection(url,"postgres","");var s=c.createStatement()) {
                String[] emails={"parent@one.test","parent@two.test","parent_2@three.test","odd+prefix@example.test"};
                List<UUID> ids=new ArrayList<>();
                for(int i=0;i<emails.length;i++) {
                    var id=UUID.randomUUID();ids.add(id);
                    try(var insert=c.prepareStatement("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,timestamptz '2026-01-01T00:00:00Z'+(? * interval '1 second'))")) {
                        insert.setObject(1,id);insert.setString(2,emails[i]);insert.setString(3,"unchanged-migration-fixture");insert.setInt(4,i);insert.executeUpdate();
                    }
                }
                UUID child=UUID.randomUUID();
                try(var insert=c.prepareStatement("INSERT INTO children(id,display_name,birth_date,created_by) VALUES(?,'Existing child','2026-01-01',?)")){insert.setObject(1,child);insert.setObject(2,ids.get(0));insert.executeUpdate();}
                Flyway.configure().dataSource(url,"postgres","").load().migrate();
                Map<String,String> usernames=new LinkedHashMap<>();
                try(var rows=s.executeQuery("SELECT id,email,username,password_hash FROM users ORDER BY created_at")) {
                    while(rows.next()) {
                        assertThat(ids).contains(rows.getObject("id",UUID.class));
                        assertThat(rows.getString("password_hash")).isEqualTo("unchanged-migration-fixture");
                        assertThat(rows.getString("username")).matches("[a-z0-9][a-z0-9._-]{0,31}");
                        usernames.put(rows.getString("email"),rows.getString("username"));
                    }
                }
                assertThat(usernames).hasSize(4);assertThat(new HashSet<>(usernames.values())).hasSize(4);
                assertThat(usernames.get(emails[0])).isEqualTo("parent");assertThat(usernames.get(emails[1])).isEqualTo("parent_2");assertThat(usernames.get(emails[2])).isEqualTo("parent_2_2");
                try(var row=s.executeQuery("SELECT id,created_by FROM children")){assertThat(row.next()).isTrue();assertThat(row.getObject("id",UUID.class)).isEqualTo(child);assertThat(row.getObject("created_by",UUID.class)).isEqualTo(ids.get(0));}
            }
        }
    }
}
