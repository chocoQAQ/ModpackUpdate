package dev.modpackupdate.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class Downloads {
    private final HttpClient client;
    private final int timeout;
    private final Set<Path> verifiedThisSession = new HashSet<>();
    private volatile InputStream active;
    private volatile boolean cancelled;
    void cancel() {
        cancelled = true;
        InputStream input = active;
        if (input != null) try { input.close(); } catch (IOException ignored) {}
    }
    Downloads(int timeout) {
        this.timeout = Math.max(2, Math.min(timeout, 120));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private URI allowed(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))) {
                throw new IOException("下载地址需要 HTTPS " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) { throw new IOException("无效下载地址", e); }
    }
    void fetch(String url, Path destination, long limit) throws Exception {
        fetch(url, destination, limit, Map.of());
    }
    void fetch(String url, Path destination, long limit, Map<String, String> headers) throws Exception {
        checkCancelled();
        URI uri = allowed(url);
        Files.createDirectories(destination.getParent());
        for (int redirect = 0; redirect <= 5; redirect++) {
            checkCancelled();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                    .header("User-Agent", "ModpackUpdate/0.2.0");
            headers.forEach(request::header);
            HttpResponse<InputStream> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                active = input;
                checkCancelled();
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    uri = allowed(uri.resolve(response.headers().firstValue("Location").orElseThrow()).toString());
                    continue;
                }
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                long expected = response.headers().firstValueAsLong("Content-Length").orElse(0);
                if (expected > limit) throw new IOException("下载超过声明大小");
                Storage.requireSpace(destination.getParent(), Math.max(0, expected));
                // Bound body reads too: the HTTP request timeout alone does not cover a streamed body.
                var watchdog = Executors.newSingleThreadScheduledExecutor();
                var deadline = watchdog.schedule(() -> { try { input.close(); } catch (IOException ignored) {} },
                        Math.max(timeout, limit > 16 * 1024 * 1024 ? 1800 : timeout), TimeUnit.SECONDS);
                try (var output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[128 * 1024];
                    long total = 0;
                    int length;
                    while ((length = input.read(buffer)) >= 0) {
                        checkCancelled();
                        total += length;
                        if (total > limit) throw new IOException("下载超过声明大小");
                        if (total / (4 * 1024 * 1024) != (total - length) / (4 * 1024 * 1024)) Storage.requireSpace(destination.getParent(), length);
                        output.write(buffer, 0, length);
                    }
                } finally { deadline.cancel(false); watchdog.shutdownNow(); }
                return;
            } finally { active = null; }
        }
        throw new IOException("重定向过多");
    }
    private void checkCancelled() throws IOException {
        CheckSession.checkInterrupted();
        if (cancelled) throw new java.io.InterruptedIOException("下载已取消");
    }
    Path artifact(Models.Artifact artifact, Path cache) throws Exception {
        if (artifact == null || artifact.size() < 0 || artifact.size() > 20L * 1024 * 1024 * 1024) throw new IOException("无效下载产物");
        return verified(List.of(artifact.url()), artifact.sha512(), artifact.size(), cache);
    }
    Path verified(List<String> urls, String hash, long size, Path cache) throws Exception {
        FilesSupport.requireHash(hash);
        Files.createDirectories(cache);
        Path file = cache.resolve(hash);
        if (Files.isRegularFile(file) && Files.size(file) == size
                && (verifiedThisSession.contains(file) || FilesSupport.hash(file).equals(hash))) {
            verifiedThisSession.add(file);
            return file;
        }
        Exception failure = new IOException("没有可用下载地址");
        for (String url : urls) {
            Path part = cache.resolve(hash + ".part");
            try {
                fetch(url, part, size);
                if (Files.size(part) != size || !FilesSupport.hash(part).equals(hash)) throw new IOException("下载校验失败");
                FilesSupport.replace(part, file);
                verifiedThisSession.add(file);
                return file;
            } catch (Exception e) { failure = e; }
            finally { Files.deleteIfExists(part); }
        }
        throw failure;
    }
}
