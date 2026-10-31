package com.aicoo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CryMigrationTest {
    @Test void separatesOldMergedOccurrencesWithoutLosingFeedbackOrRawEvents() throws Exception {
        try(var pg=LocalPostgres.builder().start()) {
            String url=pg.getJdbcUrl("postgres","postgres");
            Flyway.configure().dataSource(url,"postgres","").target("9").load().migrate();
            try(var c=DriverManager.getConnection(url,"postgres","");var s=c.createStatement()) {
                String user=UUID.randomUUID().toString(),ws=UUID.randomUUID().toString(),child=UUID.randomUUID().toString(),link=UUID.randomUUID().toString(),member=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
                s.execute("INSERT INTO users(id,username,password_hash) VALUES('"+user+"','migration','fixture')");
                s.execute("INSERT INTO workspaces(id,type,name,created_by) VALUES('"+ws+"','PERSONAL','fixture','"+user+"')");
                s.execute("INSERT INTO memberships(id,workspace_id,user_id,role) VALUES('"+member+"','"+ws+"','"+user+"','owner')");
                s.execute("INSERT INTO children(id,display_name,birth_date,created_by) VALUES('"+child+"','fixture','2026-01-01','"+user+"')");
                s.execute("INSERT INTO workspace_children(id,workspace_id,child_id) VALUES('"+link+"','"+ws+"','"+child+"')");
                s.execute("INSERT INTO devices(id,workspace_id,workspace_child_id,child_id,device_type) VALUES('"+device+"','"+ws+"','"+link+"','"+child+"','camera')");
                for(int i=0;i<4;i++) {
                    String time="2026-01-01T00:0"+(i/2)+":"+(i%2==0?"00":"30")+"Z";
                    String kind=i%2==0?"cry_started":"cry_ended";
                    // Shipped candidate events have unrelated start/end IDs and no startedAt.
                    s.execute("INSERT INTO sky_eye_events(device_id,event_id,observed_at,body) VALUES('"+device+"','event"+i+"','"+time+"','{\"id\":\"event"+i+"\",\"episodeKey\":\"key"+i+"\",\"kind\":\""+kind+"\",\"observedAt\":\""+time+"\"}')");
                }
                UUID original;
                try(var row=s.executeQuery("SELECT id FROM detection_events ORDER BY started_at LIMIT 1")){row.next();original=row.getObject(1,UUID.class);}
                s.execute("INSERT INTO care_feedback(id,event_id,membership_id,workspace_id,response,note,idempotency_key,actions) VALUES('"+UUID.randomUUID()+"','"+original+"','"+member+"','"+ws+"','continues','preserve me','fixture','[\"hug\"]')");
                Flyway.configure().dataSource(url,"postgres","").load().migrate();
                try(var rows=s.executeQuery("SELECT id,extract(epoch from ended_at-started_at)::int AS seconds,cry_alert_id FROM detection_events WHERE NOT cry_retired ORDER BY started_at")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getObject("id",UUID.class)).isEqualTo(original);UUID alert=rows.getObject("cry_alert_id",UUID.class);assertThat(rows.getInt("seconds")).isEqualTo(30);
                    assertThat(rows.next()).isTrue();assertThat(rows.getInt("seconds")).isEqualTo(30);assertThat(rows.getObject("cry_alert_id",UUID.class)).isEqualTo(alert);assertThat(rows.next()).isFalse();
                }
                try(var row=s.executeQuery("SELECT event_id,note FROM care_feedback")){assertThat(row.next()).isTrue();assertThat(row.getObject(1,UUID.class)).isEqualTo(original);assertThat(row.getString(2)).isEqualTo("preserve me");}
                try(var row=s.executeQuery("SELECT count(*) FROM sky_eye_events")){row.next();assertThat(row.getInt(1)).isEqualTo(4);}
                try(var row=s.executeQuery("SELECT count(*) FROM outbox_messages")){row.next();assertThat(row.getInt(1)).isZero();}
            }
        }
    }
}
