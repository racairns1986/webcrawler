package com.monzo.webcrawler;

import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class WebcrawlerApplication implements CommandLineRunner
{
    private static final int WORKER_COUNT = 20;
    private static final PageFetcher PAGE_FETCHER = new PageFetcher();
    private static final Queue<PageResult> COMPLETED_RESULTS = new ConcurrentLinkedQueue<>();
    private static final Set<String> foundUrls = ConcurrentHashMap.newKeySet();
    private static final Queue<String> queueToCrawl = new ConcurrentLinkedQueue<>();
    private static final Logger LOG = LoggerFactory.getLogger(WebcrawlerApplication.class);

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

        String seedUrl = args[0].split("#", 2)[0];
        String allowedHost = URI.create(seedUrl).getHost();

        if (foundUrls.add(seedUrl))
        {
            queueToCrawl.add(seedUrl);
        }

        try (ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT))
        {
            processQueuedPages(allowedHost, workers);
        }

        presentResults();
    }

    private static void presentResults()
    {
        List<PageResult> successfulResults = new ArrayList<>();
        List<PageResult> unsuccessfulResults = new ArrayList<>();
        for (PageResult pageResult : COMPLETED_RESULTS)
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

        System.out.println("Here are the successful results:");
        for (PageResult pageResult : successfulResults)
        {
            System.out.println("Visited: " + pageResult.url());
            for (String link : pageResult.links())
            {
                System.out.println("  " + link);
            }
        }
        System.out.println("Here are the unsuccessful results:");
        for (PageResult pageResult : unsuccessfulResults)
        {
            System.out.printf(
                    "Attempted: %s | Outcome: %s | HTTP status: %s%n",
                    pageResult.url(),
                    pageResult.crawlOutcome(),
                    pageResult.statusCode()
            );
            for (String link : pageResult.links())
            {
                System.out.println("  " + link);
            }
        }

        System.out.printf("Total URLs attempted: %d%n", successfulResults.size() + unsuccessfulResults.size());
        System.out.printf("Pages inspected successfully: %d%n", successfulResults.size());
        System.out.printf("Failed attempts: %d%n", unsuccessfulResults.size());

        LOG.debug("Discovered URLs: {} | Completed results: {}", foundUrls.size(), COMPLETED_RESULTS.size());
    }

    private static void crawl(String pageUrl, String allowedHost)
    {
        PageResult pageResult = PAGE_FETCHER.retrieveHTML(pageUrl);

        for (String url : pageResult.links())
        {
            String nextHost = URI.create(url).getHost();

            if (allowedHost.equalsIgnoreCase(nextHost) && foundUrls.add(url))
            {
                queueToCrawl.add(url);
            }
        }

        COMPLETED_RESULTS.add(pageResult);
    }

    private static boolean isValidURL(String url)
    {
        UrlValidator validator = new UrlValidator(new String[] { "http", "https" }, UrlValidator.ALLOW_LOCAL_URLS);

        return validator.isValid(url);
    }

    private static void processQueuedPages(String allowedHost, ExecutorService workers)
            throws InterruptedException, ExecutionException
    {
        CompletionService<Void> completions = new ExecutorCompletionService<>(workers);

        int outstanding = 0;

        while (!queueToCrawl.isEmpty() || outstanding > 0)
        {
            // Fill available worker slots.
            while (outstanding < WORKER_COUNT)
            {
                String pageUrl = queueToCrawl.poll();

                if (pageUrl == null)
                {
                    break;
                }

                completions.submit(() -> crawl(pageUrl, allowedHost), null);

                outstanding++;
            }

            if (outstanding > 0)
            {
                // Wait for a completed task and surface unexpected failures.
                completions.take().get();
                outstanding--;
            }
        }
    }
}
