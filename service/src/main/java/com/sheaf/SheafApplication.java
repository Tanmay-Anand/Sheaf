package com.sheaf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class SheafApplication {

    public static void main(String[] args) {
        avoidSpacesInSocketDirectory();
        SpringApplication.run(SheafApplication.class, args);
    }

    /**
     * Java 21 creates loopback socket files (for Tomcat's selector and the HTTP client that calls model
     * providers) in the temp directory, and that fails with "Invalid argument" when the path has a
     * space, as Windows user folders often do. Unless set explicitly, use a folder under the working
     * directory instead.
     */
    static void avoidSpacesInSocketDirectory() {
        if (System.getProperty("jdk.net.unixdomain.tmpdir") != null) return;
        if (!System.getProperty("java.io.tmpdir", "").contains(" ")) return;
        Path dir = Path.of(System.getProperty("user.dir"), "target", "sockets").toAbsolutePath();
        if (dir.toString().contains(" ")) return;
        try {
            Files.createDirectories(dir);
            System.setProperty("jdk.net.unixdomain.tmpdir", dir.toString());
        } catch (IOException e) {
            // Leave it; the documented -Djdk.net.unixdomain.tmpdir flag still works.
        }
    }
}
