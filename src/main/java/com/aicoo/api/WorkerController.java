package com.aicoo.api;

import com.aicoo.monitoring.MonitoringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Private network endpoints; X-Worker-Key is enforced before reaching this controller. */
@RestController @RequestMapping("/api/v1/internal")
public class WorkerController {
    private final MonitoringService monitoring;
    public WorkerController(MonitoringService monitoring){this.monitoring=monitoring;}
    public record Token(@NotBlank @Size(max=128)String token){}
    @PostMapping("/devices") Object device(@RequestBody @Valid MonitoringService.Device body){return monitoring.createDevice(body);}
    @PostMapping("/devices/{id}/heartbeat") Object heartbeat(@PathVariable UUID id,@RequestBody @Valid MonitoringService.Heartbeat body){return monitoring.heartbeat(id,body);}
    @PostMapping("/devices/{id}/readings") Object reading(@PathVariable UUID id,@RequestBody @Valid MonitoringService.Sensor body){return monitoring.sensor(id,body);}
    @PostMapping("/stream-tokens/introspect") Object introspect(@RequestBody @Valid Token body){return monitoring.introspect(body.token());}
}
