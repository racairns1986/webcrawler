package com.monzo.webcrawler;

import org.jsoup.nodes.Document;

public record FetchResult(
        Document document,
        Integer statusCode,
        CrawlOutcome outcome
) {}
