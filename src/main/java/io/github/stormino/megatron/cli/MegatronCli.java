package io.github.stormino.megatron.cli;

import io.github.stormino.megatron.BatchResult;
import io.github.stormino.megatron.Megatron;
import io.github.stormino.megatron.download.DownloadControl;
import io.github.stormino.megatron.download.DownloadListener;
import io.github.stormino.megatron.download.DownloadResult;
import io.github.stormino.megatron.download.DownloadSettings;
import io.github.stormino.megatron.proxy.ProxyInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal command-line front end, mostly for smoke tests of the library. */
public final class MegatronCli {

    private MegatronCli() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of("downloads");
        Path cache = Path.of("megatron-proxy-cache.json");
        Path proxyFile = null;
        DownloadSettings.Builder settings = DownloadSettings.builder();
        int parallelFiles = 1;
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o", "--out" -> out = Path.of(args[++i]);
                case "-c", "--connections" -> settings.connectionsPerFile(Integer.parseInt(args[++i]));
                case "--chunk-mb" -> settings.chunkSize(Long.parseLong(args[++i]) * 1024 * 1024);
                case "--files" -> parallelFiles = Integer.parseInt(args[++i]);
                case "--proxies" -> proxyFile = Path.of(args[++i]);
                case "--cache" -> cache = Path.of(args[++i]);
                case "--no-cache" -> cache = null;
                case "--direct-resolve" -> settings.resolveViaProxy(false);
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> urls.add(args[i]);
            }
        }
        if (urls.isEmpty()) {
            usage();
            System.exit(2);
        }
        Megatron.Builder builder = Megatron.builder()
                .downloadSettings(settings.build())
                .maxConcurrentFiles(parallelFiles)
                .cacheFile(cache)
                .validationProgress((stage, done, total, alive) -> {
                    if (done % 100 == 0 || done == total) {
                        System.err.printf("\rvalidating [%s] %d/%d, %d ok      ", stage, done, total, alive);
                    }
                });
        if (proxyFile != null) {
            List<ProxyInfo> proxies = Files.readAllLines(proxyFile).stream()
                    .map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).map(ProxyInfo::parse).toList();
            builder.proxies(proxies);
        }
        DownloadControl control = new DownloadControl();
        Runtime.getRuntime().addShutdownHook(new Thread(control::cancel));
        try (Megatron megatron = builder.build()) {
            System.err.println("Preparing proxy pool...");
            megatron.warmUp();
            System.err.printf("%n%d proxies ready%n", megatron.pool().size());
            BatchResult result = megatron.downloadAll(urls, out, new ConsoleListener(megatron), control);
            System.err.println();
            for (DownloadResult r : result.completed()) {
                System.out.printf("OK    %s (%s)%n", r.path(), human(r.size()));
            }
            result.failed().forEach((url, e) -> System.out.printf("FAIL  %s: %s%n", url, e.getMessage()));
            if (!result.allSucceeded()) {
                System.exit(1);
            }
        }
    }

    private static void usage() {
        System.err.println("""
                usage: megatron [options] <mega link>...
                  -o, --out DIR          output directory (default: downloads)
                  -c, --connections N    parallel connections per file (default: 10)
                      --chunk-mb N       chunk size in MiB (default: 32)
                      --files N          files downloaded at once (default: 1)
                      --proxies FILE     use this proxy list (host:port or scheme://host:port per line)
                      --cache FILE       proxy cache file (default: megatron-proxy-cache.json)
                      --no-cache         do not read or write a proxy cache
                      --direct-resolve   resolve links without a proxy""");
    }

    static String human(double bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (bytes >= 1024 && u < units.length - 1) {
            bytes /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", bytes, units[u]);
    }

    private record ConsoleListener(Megatron megatron) implements DownloadListener {
        @Override
        public void onResolved(String url, String fileName, long size, Path target) {
            System.err.printf("%n%s (%s) -> %s%n", fileName, human(size), target);
        }

        @Override
        public void onProgress(String url, long bytesDone, long totalBytes, double bytesPerSecond) {
            double pct = totalBytes == 0 ? 100 : bytesDone * 100.0 / totalBytes;
            System.err.printf(Locale.ROOT, "\r  %5.1f%%  %s / %s  %s/s  proxies: %d   ", pct, human(bytesDone),
                    human(totalBytes), human(bytesPerSecond), megatron.pool().size());
        }
    }
}
