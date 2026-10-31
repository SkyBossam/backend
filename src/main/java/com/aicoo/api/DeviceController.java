package com.aicoo.api;

import com.aicoo.device.DeviceGateway;
import com.aicoo.monitoring.MonitoringService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/device")
public class DeviceController {
    private final DeviceGateway gateway;
    public DeviceController(DeviceGateway gateway){this.gateway=gateway;}
    @GetMapping("/me") Object me(@RequestHeader(value="X-Device-Token",defaultValue="")String token){return gateway.me(token);}
    @PostMapping("/heartbeat") Object heartbeat(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestBody @Valid MonitoringService.Heartbeat body){return gateway.heartbeat(token,body);}
    @PostMapping("/readings") Object readings(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestBody @Valid MonitoringService.Sensor body){return gateway.readings(token,body);}
    @PostMapping("/detections") Object detection(@RequestHeader(value="X-Device-Token",defaultValue="")String token,@RequestBody @Valid DeviceGateway.Detection body){return gateway.detection(token,body);}
}
