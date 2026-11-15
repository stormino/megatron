# Megatron

Java 21 library that downloads public **Mega.nz** files and folders through a rotating pool of
free proxies. Each file is split into fixed-size chunks that are fetched in parallel, each one
through a **different proxy**, decrypted on the fly and written straight to disk.

It is a port of the ideas in
[mega-downloader-proxy-rotator](https://github.com/SuperPHaze/mega-downloader-proxy-rotator)
(Python/PyQt6), rebuilt as a headless, embeddable library with no GUI.

> Only download files you own or have the right to download. Using proxies to get around a
> service's limits may break its terms of use, and free public proxies are run by unknown third
> parties. Mega payloads are end-to-end encrypted, so proxies only see ciphertext, but they do
> see which CDN URLs you fetch.

## Features

- **Mega built in.** Parses public file and folder links, including legacy `#!`/`#F!` forms and
  folder links with a selected file or subfolder. Decrypts keys and attributes and expands shared
  folders recursively. It does not depend on any Mega SDK.
- **Proxy harvesting.** Ships with 66 public proxy lists (HTTP, SOCKS5, SOCKS4) in several
  formats. Candidates are checked in two stages (*can it connect at all?* then *can it reach
  Mega?*), with an optional third stage that measures real speed.
- **Scored pool.** Each success raises a proxy's score and each failure lowers it. Proxies that
  fall too low are dropped. When the CDN rate-limits a proxy (403/509), the proxy is benched for a
  while instead of being killed. A background refresher refills the pool, with hysteresis so it
  does not refill in a loop. Good proxies are saved to an on-disk cache so the next start is fast.
- **Parallel chunked download.** Fixed chunks of 32 MiB by default, fetched over 10 Range
  connections by default. Decryption uses AES-CTR and memory use stays constant. The file is
  written to `<name>.part` and atomically renamed when complete.
- **Integrity check.** Before the final rename, the file is checked against the meta-MAC that Mega
  embeds in every file key. On a mismatch, the corrupt data is discarded and the file is downloaded
  again. Turn it off with `DownloadSettings.Builder.verifyMac(false)`.
- **Granular resume.** A sidecar file records which chunks are finished. A failure costs at most
  one chunk, and resume still works if you change the number of connections between runs.
- **Resilience:**
  - The `Retry-After` header is honoured.
  - 503 responses kill that proxy and fetch a fresh CDN URL.
  - On 429 the same proxy is retried after a pause.
  - Each chunk attempt has a time budget and a minimum-speed watchdog.
  - Each file is retried as a whole a limited number of times.
- **Control:** pause, resume and cancel, plus listener callbacks for progress, chunks and results.

## Installation

Releases are published to GitHub Packages and attached to each
[GitHub Release](https://github.com/stormino/megatron/releases): the library jar, a runnable CLI
jar, sources and javadoc.

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/stormino/megatron</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.github.stormino</groupId>
    <artifactId>megatron</artifactId>
    <version>0.1.0</version>
</dependency>
```

GitHub Packages requires authentication even for public packages. Add a `github` server with
your username and a token that has the `read:packages` scope to `~/.m2/settings.xml`.

## Usage

```java
try (Megatron megatron = Megatron.builder()
        .cacheFile(Path.of("proxy-cache.json"))     // hot start next time
        .build()) {

    // single file
    DownloadResult r = megatron.download("https://mega.nz/file/XXXX#YYYY", Path.of("downloads"));

    // many links, folders expanded into downloads/<folder name>/...
    BatchResult batch = megatron.downloadAll(
            List.of("https://mega.nz/folder/AAAA#BBBB", "https://mega.nz/file/CCCC#DDDD"),
            Path.of("downloads"),
            new DownloadListener() {
                @Override
                public void onProgress(String url, long done, long total, double bps) {
                    System.out.printf("%s %.1f%%%n", url, done * 100.0 / total);
                }
            },
            new DownloadControl());   // keep a reference to pause()/resume()/cancel()
}
```

The first call fills the pool by scraping and validating proxies, which can take a few minutes.
Call `megatron.warmUp()` to do that up front, and pass
`Megatron.Builder.validationProgress(...)` to watch it.

### Your own proxies

```java
Megatron.builder()
        .proxies(List.of(ProxyInfo.parse("http://user:pass@1.2.3.4:8080"),
                         ProxyInfo.parse("socks5://5.6.7.8:1080")))
        .build();
```

With a static list, nothing is scraped and no refresher runs.

### Tuning

```java
Megatron.builder()
        .downloadSettings(DownloadSettings.builder()
                .connectionsPerFile(16)
                .chunkSize(16L * 1024 * 1024)
                .minThroughputBps(100 * 1024)   // 0 disables the speed watchdog
                .resolveViaProxy(false)         // resolve links directly (API calls are not quota-limited)
                .build())
        .maxConcurrentFiles(2)
        .selectionMode(ProxyPool.SelectionMode.THROUGHPUT)
        .validatorSettings(ProxyValidator.Settings.defaults().withSpeedTest(true))
        .build();
```

The lower-level pieces can also be used on their own: `MegaApiClient`, `FolderExpander`,
`ProxyScraper`, `ProxyValidator`, `ProxyPool`, `PoolRefresher` and `ParallelDownloader`.

## CLI

```bash
mvn -Pcli package
java -jar target/megatron-0.1.0-SNAPSHOT-cli.jar -o downloads "https://mega.nz/file/XXXX#YYYY"
java -jar target/megatron-0.1.0-SNAPSHOT-cli.jar --help
```

## Build

```bash
mvn verify          # build + tests
mvn -Pcli verify    # also the runnable CLI jar
```

## Versioning and releases

The project follows [Semantic Versioning](https://semver.org). `main` always carries the next
version with `-SNAPSHOT`. Only the **Release** workflow produces release versions.

- **CI** (`.github/workflows/ci.yml`) builds and tests every pull request and every push to `main`.
  Each push to `main` also publishes the current `-SNAPSHOT` to GitHub Packages.
- **Release** (`.github/workflows/release.yml`) is started by hand from the Actions tab on `main`,
  or with `gh workflow run release.yml -f version=X.Y.Z`. It:
  1. sets the pom to `X.Y.Z` and runs the full build and tests;
  2. publishes `X.Y.Z` to GitHub Packages;
  3. commits `Release X.Y.Z`, tags `vX.Y.Z` and creates the GitHub Release, with notes generated
     from merged pull requests and the jars attached;
  4. moves `main` to the next `-SNAPSHOT` version (the next patch by default; `next_version`
     overrides it).

  If `version` is left empty, it releases the current snapshot version. Release notes group pull
  requests by label (`breaking`, `enhancement`, `bug`, `dependencies`, ...), as configured in
  `.github/release.yml`.
- **Dependabot** opens weekly pull requests for Maven dependencies and GitHub Actions.

Which number to bump:
- **major**: breaking change to the public API;
- **minor**: new backwards-compatible feature;
- **patch**: bug fix.

While the version is `0.x`, minor releases may still break the API.

## Package map

| Package    | Contents |
|------------|----------|
| `mega`     | `MegaLinks` (URL forms), `MegaCrypto` (AES-CBC/ECB/CTR, key derivation), `MegaMac` (integrity), `MegaApiClient`, `FolderExpander` |
| `proxy`    | `ProxySources`, `ProxyScraper`, `ProxyValidator`, `ProxyPool`, `PoolRefresher`, `ProxyCache`, `ProxyHarvester` |
| `download` | `ParallelDownloader`, `DownloadSettings`, `DownloadListener`, `DownloadControl`, resume sidecar |
| `http`     | `HttpClientFactory`: OkHttp clients routed through a given proxy |
| root       | `Megatron` facade and `BatchResult` |

## Known limitations

- The meta-MAC can't point to the bad chunk, so a mismatch means downloading the whole file again.
- SOCKS4-only proxies usually fail validation, because the JDK SOCKS client cannot send hostnames
  over SOCKS4. The default sources therefore cover only HTTP and SOCKS5.
- Free proxies are poor. In a live test, about 35% of candidates answered at all, and under 1% of
  those could open an HTTPS tunnel to Mega (most refuse `CONNECT` or require a login). Expect a pool
  of tens of proxies from 12,000 candidates. That was still enough for 165 MB at up to 7 MB/s.
