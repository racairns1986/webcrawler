package com.monzo.webcrawler;

import org.apache.commons.validator.routines.UrlValidator;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.net.URI;
import java.time.Instant;
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
        LOG.info("Total URLs attempted: {}", successfulResults.size() + unsuccessfulResults.size());
        LOG.info("Pages inspected successfully: {}", successfulResults.size());
        LOG.info("Failed attempts: {}", unsuccessfulResults.size());
        LOG.info("Discovered URLs: {} | Completed results: {}", foundUrls.size(), COMPLETED_RESULTS.size());
        System.out.println("Here are the successful results:");
        for(PageResult pageResult : successfulResults){
            System.out.println("Visited: " + pageResult.url());
            for (String link : pageResult.links()) {
                System.out.println("  " + link);
            }
        }
        System.out.println("Here are the unsuccessful results:");
        for(PageResult pageResult : unsuccessfulResults){
            System.out.println("Visited: " + pageResult.url());
            for (String link : pageResult.links()) {
                System.out.println("  " + link);
            }
        }

    }

    private static void crawl(String pageUrl, String allowedHost)
    {
        LOG.debug("Crawling: {}", pageUrl);

        // call the retrieveHTML function to fetch HTML of the current page
        FetchResult fetchResult = PAGE_FETCHER.retrieveHTML(pageUrl);

        if (fetchResult.document() != null)
        {
            inspectPage(fetchResult, pageUrl, allowedHost);
        }
        else
        {
            List<String> listOfLinks = new ArrayList<String>();
            PageResult pageResult = new PageResult(pageUrl, listOfLinks, fetchResult.outcome(),
                    fetchResult.statusCode(), Instant.now());
            COMPLETED_RESULTS.add(pageResult);
        }
    }

    private static void inspectPage(FetchResult fetchResult, String pageUrl, String allowedHost)
    {
        List<String> linksInPage = new ArrayList<>();

        // find all links on the page
        Elements links = fetchResult.document().select("a[href]");
        for (Element link : links)
        {
            // get absolute URL
            String nextUrl = link.absUrl("href");
            if (isValidURL(nextUrl))
            {
                String normalizedUrl = nextUrl.split("#", 2)[0];
                linksInPage.add(normalizedUrl);
                String nextHost = URI.create(normalizedUrl).getHost();
                if (allowedHost.equalsIgnoreCase(nextHost) && foundUrls.add(normalizedUrl))
                {
                    queueToCrawl.add(normalizedUrl);
                }
            }
        }
        PageResult pageResult = new PageResult(pageUrl, linksInPage, fetchResult.outcome(), fetchResult.statusCode(),
                Instant.now());
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
