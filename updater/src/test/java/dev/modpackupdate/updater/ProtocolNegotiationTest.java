package dev.modpackupdate.updater;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolNegotiationTest {
    @TempDir Path root;

    private Models.Release release(int protocol) {
        var artifact = new Models.Artifact("https://example.com/artifact", "a".repeat(128), 100);
        return protocol == 2
                ? new Models.Release(2, "pack", 2, "2.0", null, null, null, null, artifact)
                : new Models.Release(1, "pack", 2, "2.0", "", artifact,
                        new Models.Overrides(false, null, List.of()), artifact);
    }

    @Test void sameUrlServesLegacyAndModernClientsAndKeepsHeaderAcrossRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var headers = new CopyOnWriteArrayList<String>();
        server.createContext("/latest", exchange -> {
            headers.add("entry:" + exchange.getRequestHeaders().getFirst(Protocol.REQUEST_PROTOCOL_HEADER));
            exchange.getResponseHeaders().set("Location", "/negotiated?channel=stable");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/negotiated", exchange -> {
            String value = exchange.getRequestHeaders().getFirst(Protocol.REQUEST_PROTOCOL_HEADER);
            headers.add("target:" + value);
            byte[] body = FilesSupport.JSON.toJson(release("2".equals(value) ? 2 : 1)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Vary", Protocol.REQUEST_PROTOCOL_HEADER);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path legacy = root.resolve("legacy.json");
            new Downloads(2).fetch(base + "/latest", legacy, 65536);
            assertEquals(1, FilesSupport.read(legacy, Models.Release.class).protocolVersion());
            Protocol protocol = new Protocol(new Downloads(2), root.resolve("cache"));
            assertEquals(2, protocol.release(base + "/latest", "pack").protocolVersion());
            assertEquals(List.of("entry:null", "target:null", "entry:2", "target:2"), headers);
        } finally { server.stop(0); }
    }

    @Test void staticV1EndpointCanIgnoreTheRequestHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var headers = new CopyOnWriteArrayList<String>();
        server.createContext("/latest", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst(Protocol.REQUEST_PROTOCOL_HEADER));
            byte[] body = FilesSupport.JSON.toJson(release(1)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Protocol protocol = new Protocol(new Downloads(2), root.resolve("cache"));
            assertEquals(1, protocol.release("http://127.0.0.1:" + server.getAddress().getPort() + "/latest", "pack").protocolVersion());
            assertEquals(List.of("2"), headers);
        } finally { server.stop(0); }
    }
}
