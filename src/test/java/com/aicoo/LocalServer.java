package com.aicoo;

import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.util.Map;

/** Development launcher only. Never included in the production JAR. */
public class LocalServer {
    public static void main(String[] args)throws Exception{
        Path data=Path.of("data","postgres").toAbsolutePath();Files.createDirectories(data);
        var postgres=LocalPostgres.builder().setDataDirectory(data).setCleanDataDirectory(false).start();
        System.setProperty("spring.datasource.url",postgres.getJdbcUrl("postgres","postgres"));
        System.setProperty("spring.datasource.username","postgres");System.setProperty("spring.datasource.password","");
        System.setProperty("server.address","127.0.0.1");System.setProperty("app.environment","development");System.setProperty("app.mail-mode","local");
        var app=new SpringApplication(AicooApplication.class);app.setDefaultProperties(Map.of("server.port",8080));app.run(args);
    }
}
