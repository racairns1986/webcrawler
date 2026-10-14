package com.monzo.webcrawler;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;

public class PageFetcher
{
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    public FetchResult retrieveHTML(String url)
    {
        long startedNanos = System.nanoTime();
        Integer statusCode = null;

        System.err.printf("Fetching: %s%n", url);

        try
        {
            Connection.Response response = Jsoup.connect(url)
                    .timeout(REQUEST_TIMEOUT_MILLIS)
                    .followRedirects(false)
                    .execute();

            statusCode = response.statusCode();

            System.err.printf(
                    "Response: %s | HTTP %d | Content-Type: %s%n",
                    url, statusCode, response.contentType()
            );

            // Consume and close the response body, including for redirects.
            response.readFully();

            if (statusCode < 200 || statusCode >= 300)
            {
                System.err.printf(
                        "Page not inspected: %s | HTTP %d | Location: %s%n",
                        url, statusCode, response.header("Location")
                );

                return new FetchResult(null, statusCode, CrawlOutcome.FAILED);
            }

            Document document = response.parse();
            System.err.printf("HTML parsed successfully: %s%n", url);

            return new FetchResult(document, statusCode, CrawlOutcome.SUCCEEDED);
        }
        catch (HttpStatusException e)
        {
            System.err.printf(
                    "HTTP failure: %s | HTTP %d | %s%n",
                    url, e.getStatusCode(), e.getMessage()
            );

            return new FetchResult(null, e.getStatusCode(), CrawlOutcome.FAILED);
        }
        catch (SocketTimeoutException | HttpTimeoutException e)
        {
            System.err.printf(
                    "Timed out: %s | %s%n",
                    url, e.getMessage()
            );

            return new FetchResult(null, statusCode, CrawlOutcome.TIMED_OUT);
        }
        catch (IOException | IllegalArgumentException e)
        {
            System.err.printf(
                    "Fetch failed: %s | %s | %s%n",
                    url, e.getClass().getSimpleName(), e.getMessage()
            );

            return new FetchResult(null, statusCode, CrawlOutcome.FAILED);
        }
        finally
        {
            long elapsedMillis =
                    (System.nanoTime() - startedNanos) / 1_000_000;

            System.err.printf(
                    "Attempt finished: %s | %d ms | %s%n",
                    url, elapsedMillis, Instant.now()
            );
        }
    }
}
