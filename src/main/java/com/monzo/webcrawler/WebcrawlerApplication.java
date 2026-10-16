package com.monzo.webcrawler;

import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SpringBootApplication
public class WebcrawlerApplication implements CommandLineRunner
{
    private static final int WORKER_COUNT = 20;

    public static void main(String[] args)
    {
        SpringApplication.run(WebcrawlerApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception
    {
        if (args.length != 1 || !isValidURL(args[0]))
        {
            throw new IllegalArgumentException("Usage: webcrawler <valid-http-or-https-url>");
        }

        List<PageResult> results = new Crawler(args[0], new PageFetcher(), WORKER_COUNT).crawl();

        presentResults(results);
    }

    private static void presentResults(List<PageResult> results)
    {
        List<PageResult> successfulResults = new ArrayList<>();
        List<PageResult> unsuccessfulResults = new ArrayList<>();
        for (PageResult pageResult : results)
        {
            if (pageResult.crawlOutcome() == CrawlOutcome.SUCCEEDED)
            {
                successfulResults.add(pageResult);
            }
            else
            {
                unsuccessfulResults.add(pageResult);
            }
        }
        successfulResults.sort(Comparator.comparing(PageResult::url));
        unsuccessfulResults.sort(Comparator.comparing(PageResult::url));

        IO.println("Here are the successful results:");
        for (PageResult pageResult : successfulResults)
        {
            IO.println("Visited: " + pageResult.url());
            for (String link : pageResult.links())
            {
                IO.println("  " + link);
            }
        }
        IO.println("Here are the unsuccessful results:");
        for (PageResult pageResult : unsuccessfulResults)
        {
            IO.println(
                    "Attempted: %s | Outcome: %s | HTTP status: %s".formatted(pageResult.url(),
                            pageResult.crawlOutcome(),
                            pageResult.statusCode())
            );
            for (String link : pageResult.links())
            {
                IO.println("  " + link);
            }
        }

        IO.println("Total URLs attempted: %d".formatted(successfulResults.size() + unsuccessfulResults.size()));
        IO.println("Pages inspected successfully: %d".formatted(successfulResults.size()));
        IO.println("Pages not inspected: %d".formatted(unsuccessfulResults.size()));
    }

    private static boolean isValidURL(String url)
    {
        UrlValidator validator = new UrlValidator(new String[] { "http", "https" }, UrlValidator.ALLOW_LOCAL_URLS);

        return validator.isValid(url);
    }
}
