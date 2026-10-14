package com.monzo.webcrawler;

import java.time.Instant;
import java.util.List;

public record PageResult(
        String url,
        List<String> links,
        CrawlOutcome crawlOutcome,
        Integer statusCode,
        Instant completedAt
) {
    public PageResult {
        links = List.copyOf(links);
    }
}