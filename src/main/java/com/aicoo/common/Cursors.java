package com.aicoo.common;

import com.aicoo.security.Secrets;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Signed, context-bound keyset cursors. A cursor cannot be moved to another child's feed. */
@Component
public class Cursors {
    private final Secrets secrets;
    public Cursors(Secrets secrets) { this.secrets=secrets; }
    public record Position(Instant time, UUID id) {}
    public String encode(String context, Instant time, UUID id) {
        String value=Base64.getUrlEncoder().withoutPadding().encodeToString((context+"|"+time+"|"+id).getBytes(StandardCharsets.UTF_8));
        return value+"."+secrets.sign(value);
    }
    public Position decode(String context, String cursor) {
        if(cursor==null||cursor.isBlank()) return null;
        try {
            if(cursor.length()>2048) throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.");
            if(parts.length!=2||!Secrets.equal(secrets.sign(parts[0]),parts[1])) throw new IllegalArgumentException();
            String[] fields=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8).split("\\|");
            if(fields.length!=3||!fields[0].equals(context)) throw new IllegalArgumentException();
            return new Position(Instant.parse(fields[1]),UUID.fromString(fields[2]));
        } catch(RuntimeException ex) { throw ApiException.invalid("INVALID_CURSOR"); }
    }
    public static int limit(int value) { if(value<1||value>100)throw ApiException.invalid("INVALID_LIMIT");return value; }
}
