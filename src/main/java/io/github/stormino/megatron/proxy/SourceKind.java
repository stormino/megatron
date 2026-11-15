package io.github.stormino.megatron.proxy;

/** Format of a public proxy list. */
public enum SourceKind {
    /** HTML page whose first {@code <table>} has host and port in the first two columns. */
    HTML_TABLE,
    /** One {@code host:port} per line, optionally prefixed with a scheme. */
    PLAIN_TEXT,
    /** Geonode JSON: {@code {"data":[{"ip":..,"port":..}]}}. */
    GEONODE_JSON,
    /** One JSON object per line with {@code host}, {@code port}, {@code type}. */
    JSONL,
    /** Databay JSON: array, or object with {@code data}/{@code proxies}. */
    DATABAY_JSON,
    /** ProxyScrape JSON with uptime/latency metadata (pre-filtered). */
    PROXYSCRAPE_JSON
}
