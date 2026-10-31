package com.aicoo.monitoring;

import com.aicoo.common.*;
import com.aicoo.device.PairingService;
import com.aicoo.security.Policy;
import com.aicoo.security.Policy.Actor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.aicoo.common.Db.*;

/** Latest JPEG only, in memory. No public URL, recording, arbitrary source URL or disk cache. */
@Service
public class CameraRelay {
    public static final int MAX_BYTES=524288;
    public record Frame(byte[] bytes,Instant capturedAt,Instant receivedAt,int width,int height) {}
    private final Map<UUID,Frame> frames=new ConcurrentHashMap<>();
    private final PairingService pairing;private final Policy policy;private final Db db;
    public CameraRelay(PairingService pairing,Policy policy,Db db){this.pairing=pairing;this.policy=policy;this.db=db;}
    private boolean fresh(Frame f){return f!=null&&f.capturedAt().isAfter(Instant.now().minusSeconds(3))&&f.receivedAt().isAfter(Instant.now().minusSeconds(3));}
    @Transactional public Object upload(String token,Instant captured,byte[] jpeg) {
        var device=pairing.authorize(token);UUID id=id(device,"id");Instant now=Instant.now();
        if(captured==null||captured.isBefore(now.minusSeconds(5))||captured.isAfter(now.plusSeconds(5)))throw ApiException.invalid("STALE_CAMERA_FRAME");
        if(jpeg.length>MAX_BYTES)throw new ApiException(413,"FRAME_TOO_LARGE","영상 프레임이 너무 커요.");
        if(jpeg.length<4||jpeg[0]!=(byte)0xff||jpeg[1]!=(byte)0xd8||jpeg[jpeg.length-2]!=(byte)0xff||jpeg[jpeg.length-1]!=(byte)0xd9)throw ApiException.invalid("INVALID_JPEG");
        int width,height;
        var reader=ImageIO.getImageReadersByFormatName("JPEG").next();
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
            reader.setInput(input);width=reader.getWidth(0);height=reader.getHeight(0);
            if(width<1||height<1||width>1920||height>1080)throw ApiException.invalid("FRAME_DIMENSIONS_EXCEEDED");
        } catch(IOException e){throw ApiException.invalid("INVALID_JPEG");} finally {reader.dispose();}
        var previous=frames.get(id);
        if(previous!=null&&captured.isBefore(previous.capturedAt()))throw ApiException.conflict("STALE_CAMERA_FRAME");
        if(previous!=null&&previous.receivedAt().isAfter(now.minusMillis(200)))throw new ApiException(429,"FRAME_RATE_LIMIT","영상 전송 간격을 늘려 주세요.");
        if(frames.size()>=64&&!frames.containsKey(id)){cleanup();if(frames.size()>=64)throw new ApiException(503,"CAMERA_CAPACITY","잠시 후 다시 연결해 주세요.");}
        frames.put(id,new Frame(jpeg,captured,now,width,height));
        return p("accepted",true,"capturedAt",captured);
    }
    public Map<String,Object> state(UUID device) {
        var frame=frames.get(device);boolean available=fresh(frame);
        return p("available",available,"capturedAt",available?frame.capturedAt():null,"width",available?frame.width():null,"height",available?frame.height():null);
    }
    @Transactional public Frame read(Actor actor,UUID child,UUID device) {
        policy.require(actor,child,"monitor:live");
        db.required("SELECT id FROM devices WHERE id=:id AND workspace_id=:ws AND child_id=:child AND revoked_at IS NULL FOR SHARE",p("id",device,"ws",actor.workspaceId(),"child",child),"DEVICE_NOT_ACCESSIBLE");
        var frame=frames.get(device);return fresh(frame)?frame:null;
    }
    @Scheduled(fixedDelay=10000) public void cleanup(){frames.entrySet().removeIf(e->!fresh(e.getValue()));}
}
