package com.monzo.webcrawler;

import org.apache.commons.validator.routines.UrlValidator;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PageFetcher
{
    private static final Logger LOG = LoggerFactory.getLogger(PageFetcher.class);
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    public PageResult retrieveHTML(String pageUrl)
    {
        long startedNanos = System.nanoTime();
        Integer statusCode = null;

        LOG.debug("Fetching: {}", pageUrl);

        try
        {
            Connection.Response response = Jsoup.connect(pageUrl).timeout(REQUEST_TIMEOUT_MILLIS).followRedirects(false)
                    .execute();

            statusCode = response.statusCode();

            LOG.debug("Response: {} | HTTP {} | Content-Type: {}", pageUrl, statusCode, response.contentType());
            response.readFully();

            if (statusCode >= 300 && statusCode < 400)
            {
                String target = resolveRedirectTarget(pageUrl, response.header("Location"));
                LOG.info("Redirected: {} | HTTP {} | Location: {}", pageUrl, statusCode, target);
                String normalizedTarget =
                        target == null ? null : considerLink(target);

                List<String> links = normalizedTarget == null
                        ? List.of()
                        : List.of(normalizedTarget);

                return new PageResult(
                        pageUrl, links, CrawlOutcome.REDIRECTED,
                        statusCode, Instant.now()
                );
            }

            Document document = response.parse();
            LOG.debug("HTML parsed successfully: {}", pageUrl);
            return inspectPage(pageUrl, statusCode, document);
        }
        catch (HttpStatusException e)
        {
            LOG.warn("HTTP failure: {} | HTTP {} | {}", pageUrl, e.getStatusCode(), e.getMessage());
            return new PageResult(pageUrl, List.of(), CrawlOutcome.FAILED, e.getStatusCode(),  Instant.now());
        }
        catch (SocketTimeoutException | HttpTimeoutException e)
        {
            LOG.warn("Timed out: {} | HTTP status: {} | {}", pageUrl, statusCode, e.getMessage());
            return new PageResult(pageUrl, List.of(), CrawlOutcome.TIMED_OUT, statusCode,  Instant.now());
        }
        catch (IOException | IllegalArgumentException e)
        {
            LOG.warn("Fetch failed: {} | HTTP status: {} | {} | {}", pageUrl, statusCode, e.getClass().getSimpleName(),
                    e.getMessage());
            return new PageResult(pageUrl, List.of(), CrawlOutcome.FAILED, statusCode,  Instant.now());
        }
        finally
        {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            LOG.debug("Attempt finished: {} | {} ms", pageUrl, elapsedMillis);
        }
    }

    private static String resolveRedirectTarget(String requestUrl, String location)
    {
        if (location == null || location.isBlank())
        {
            return null;
        }
        try
        {
            return URI.create(requestUrl).resolve(location.trim()).toString();
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Unparseable Location header: {} | {}", requestUrl, location);
            return null;
        }
    }

    private PageResult inspectPage(String pageUrl, Integer statusCode, Document document)
    {
        List<String> linksInPage = new ArrayList<>();

        // find all links on the page
        Elements links = document.select("a[href]");
        for (Element link : links)
        {
            String normalizedUrl = considerLink(link.absUrl("href"));
            if (normalizedUrl != null)
            {
                linksInPage.add(normalizedUrl);
            }
        }
        return new PageResult(pageUrl, linksInPage, CrawlOutcome.SUCCEEDED, statusCode,
                Instant.now());
    }

    private static String considerLink(String candidate)
    {
        if (!isValidURL(candidate))
        {
            return null;
        }
        return candidate.split("#", 2)[0];
    }

    private static boolean isValidURL(String url)
    {
        UrlValidator validator = new UrlValidator(new String[] { "http", "https" }, UrlValidator.ALLOW_LOCAL_URLS);

        return validator.isValid(url);
    }
}
