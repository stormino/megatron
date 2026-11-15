package io.github.stormino.megatron.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Scrape + validate: the standard refill function of a {@link ProxyPool}.
 * Candidates are shuffled and capped before validation, so a refill does not test the same
 * head of the lists every time and stays bounded in duration.
 */
public final class ProxyHarvester implements Supplier<List<ProxyInfo>> {

    private static final Logger log = LoggerFactory.getLogger(ProxyHarvester.class);

    private final ProxyScraper scraper;
    private final ProxyValidator validator;
    private final int maxCandidates;
    private volatile ProxyValidator.ProgressListener progress;

    public ProxyHarvester(ProxyScraper scraper, ProxyValidator validator, int maxCandidates) {
        this.scraper = scraper;
        this.validator = validator;
        this.maxCandidates = maxCandidates;
    }

    public void progressListener(ProxyValidator.ProgressListener progress) {
        this.progress = progress;
    }

    @Override
    public List<ProxyInfo> get() {
        List<ProxyInfo> candidates = new ArrayList<>(scraper.fetchAll());
        Collections.shuffle(candidates);
        if (maxCandidates > 0 && candidates.size() > maxCandidates) {
            candidates = new ArrayList<>(candidates.subList(0, maxCandidates));
        }
        log.info("Validating {} candidate proxies", candidates.size());
        return validator.validate(candidates, progress);
    }
}
