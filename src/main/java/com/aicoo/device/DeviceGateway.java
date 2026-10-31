package com.aicoo.device;

import com.aicoo.common.*;
import com.aicoo.detection.EventService;
import com.aicoo.monitoring.MonitoringService;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import static com.aicoo.common.Db.*;

@Service
public class DeviceGateway {
    public record Detection(@NotBlank @Size(max=128)String sourceEventId,@NotBlank @Pattern(regexp="cry|face_covered|edge_proximity|movement|camera_obstructed|connection_lost")String type,
        @NotBlank @Pattern(regexp="immediate|caution|stable|unknown")String severity,@NotNull Instant startedAt,Instant endedAt,
        @DecimalMin("0") @DecimalMax("1")Double confidence,@Size(max=100)String modelVersion,boolean verifiable){}
    private final PairingService pairing;private final MonitoringService monitoring;private final EventService events;
    public DeviceGateway(PairingService pairing,MonitoringService monitoring,EventService events){this.pairing=pairing;this.monitoring=monitoring;this.events=events;}
    @Transactional public Object me(String token){var device=pairing.authorize(token);return p("deviceId",device.get("id"),"workspaceId",device.get("workspace_id"),"childId",device.get("child_id"),"name",device.get("display_name"));}
    @Transactional public Object heartbeat(String token,MonitoringService.Heartbeat body){var device=pairing.authorize(token);return monitoring.heartbeat(id(device,"id"),body);}
    @Transactional public Object readings(String token,MonitoringService.Sensor body){var device=pairing.authorize(token);return monitoring.sensor(id(device,"id"),body);}
    @Transactional public Object detection(String token,Detection body){var device=pairing.authorize(token);return events.ingest(new EventService.Detection(id(device,"workspace_id"),id(device,"child_id"),id(device,"id"),body.sourceEventId(),body.type(),body.severity(),body.startedAt(),body.endedAt(),body.confidence(),body.modelVersion(),body.verifiable()));}
}
