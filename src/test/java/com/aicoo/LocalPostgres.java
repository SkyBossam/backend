package com.aicoo;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.*;

/** Real PostgreSQL for development/tests. Windows native binaries need an ASCII installation path. */
final class LocalPostgres {
    private LocalPostgres(){}
    static EmbeddedPostgres.Builder builder()throws IOException{
        Path temp=Path.of(System.getProperty("java.io.tmpdir"));Files.createDirectories(temp);
        var builder=EmbeddedPostgres.builder().setPort(0).setLocaleConfig("locale","C").setServerConfig("listen_addresses","127.0.0.1");
        if(System.getProperty("os.name").toLowerCase().contains("win")){
            String nativeTemp=System.getenv("TEMP");Path binaries=Path.of(nativeTemp==null?temp.toString():nativeTemp,"aicoo-postgres-binaries");Files.createDirectories(binaries);builder.setOverrideWorkingDirectory(binaries.toFile());
        }
        return builder;
    }
}
