package com.aicoo.monitoring;

import com.aicoo.common.Db;
import com.aicoo.detection.EventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.aicoo.common.Db.*;

@Service
public class MonitoringWatchdog {
    private final Db db;private final EventService events;private final int stale;private final boolean enabled;
    public MonitoringWatchdog(Db db,EventService events,@Value("${app.monitoring-stale-seconds:30}")int stale,@Value("${app.outbox-enabled:true}")boolean enabled){this.db=db;this.events=events;this.stale=stale;this.enabled=enabled;}
    @Scheduled(fixedDelay=5000) @Transactional
    public void check(){if(!enabled)return;
        var rows=db.list("SELECT d.* FROM devices d JOIN workspace_children wc ON wc.id=d.workspace_child_id WHERE d.device_type='camera' AND d.state IN ('live','unverifiable') AND d.last_seen_at<:before AND wc.link_status='active' AND (wc.share_grant_id IS NULL OR EXISTS(SELECT 1 FROM data_share_grants s WHERE s.id=wc.share_grant_id AND s.revoked_at IS NULL AND s.expires_at>now())) ORDER BY d.last_seen_at LIMIT 50 FOR UPDATE OF d SKIP LOCKED",p("before",Instant.now().minusSeconds(stale)));
        for(var device:rows){
            db.update("UPDATE devices SET state='offline' WHERE id=:id",p("id",device.get("id")));db.update("UPDATE monitoring_sessions SET ended_at=last_updated_at WHERE source_id=:id AND ended_at IS NULL",p("id",device.get("id")));
            events.ingest(new EventService.Detection(id(device,"workspace_id"),id(device,"child_id"),id(device,"id"),"offline:"+device.get("last_seen_at"),"connection_lost","unknown",Instant.now(),null,null,"watchdog-v1",false));
        }
    }
}
