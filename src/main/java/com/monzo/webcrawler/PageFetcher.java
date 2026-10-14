package com.monzo.webcrawler;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

public class PageFetcher
{
    private static final Logger LOG = LoggerFactory.getLogger(PageFetcher.class);
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    public FetchResult retrieveHTML(String url)
    {
        long startedNanos = System.nanoTime();
        Integer statusCode = null;

        LOG.debug("Fetching: {}", url);

        try
        {
            Connection.Response response = Jsoup.connect(url).timeout(REQUEST_TIMEOUT_MILLIS).followRedirects(false)
                    .execute();

            statusCode = response.statusCode();

            LOG.debug("Response: {} | HTTP {} | Content-Type: {}", url, statusCode, response.contentType());

            // Consume and close the response body, including for redirects.
            response.readFully();

            if (statusCode < 200 || statusCode >= 300)
            {
                LOG.warn("Page not inspected: {} | HTTP {} | Location: {}", url, statusCode,
                        response.header("Location"));

                return new FetchResult(null, statusCode, CrawlOutcome.FAILED);
            }

            Document document = response.parse();
            LOG.debug("HTML parsed successfully: {}", url);

            return new FetchResult(document, statusCode, CrawlOutcome.SUCCEEDED);
        }
        catch (HttpStatusException e)
        {
            LOG.warn("HTTP failure: {} | HTTP {} | {}", url, e.getStatusCode(), e.getMessage());

            return new FetchResult(null, e.getStatusCode(), CrawlOutcome.FAILED);
        }
        catch (SocketTimeoutException | HttpTimeoutException e)
        {
            LOG.warn("Timed out: {} | HTTP status: {} | {}", url, statusCode, e.getMessage());

            return new FetchResult(null, statusCode, CrawlOutcome.TIMED_OUT);
        }
        catch (IOException | IllegalArgumentException e)
        {
            LOG.warn("Fetch failed: {} | HTTP status: {} | {} | {}", url, statusCode, e.getClass().getSimpleName(),
                    e.getMessage());

            return new FetchResult(null, statusCode, CrawlOutcome.FAILED);
        }
        finally
        {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;

            LOG.debug("Attempt finished: {} | {} ms", url, elapsedMillis);
        }
    }
}
