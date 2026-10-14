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
import java.util.concurrent.ConcurrentLinkedQueue;

@SpringBootApplication
public class WebcrawlerApplication implements CommandLineRunner
{
    private static final PageFetcher PAGE_FETCHER = new PageFetcher();
    private static final Queue<PageResult> COMPLETED_RESULTS = new ConcurrentLinkedQueue<>();
    private static Set<String> foundUrls = new HashSet<>();
    private static final Queue<String> queueToCrawl = new ConcurrentLinkedQueue<>();

    public static void main(String[] args)
    {
        SpringApplication.run(WebcrawlerApplication.class, args);
    }

    @Override
    public void run(String... args)
    {
        //Validate there is 1 argument and it is a valid URL
        if (args.length != 1 || !isValidURL(args[0]))
        {
            throw new IllegalArgumentException("Usage: webcrawler <valid-http-or-https-url>");
        }
        String seedUrl = args[0].split("#", 2)[0];
        String allowedHost = URI.create(seedUrl).getHost();

        // Claim and queue the starting URL.
        if (foundUrls.add(seedUrl))
        {
            queueToCrawl.add(seedUrl);
        }

        // Process queued URLs, including those discovered along the way.
        String nextUrl;
        while ((nextUrl = queueToCrawl.poll()) != null)
        {
            crawl(nextUrl, allowedHost);
        }
        for (PageResult pageResult : COMPLETED_RESULTS)
        {
            System.out.println("Visited: " + pageResult.url());
            for (String listedUrl : pageResult.links())
            {
                System.out.println("LinkFound: " + listedUrl);
            }
        }

    }

    private static void crawl(String pageUrl, String allowedHost)
    {
        System.out.println("Crawling: " + pageUrl);

        // call the retrieveHTML function to fetch HTML of the current page
        FetchResult fetchResult = PAGE_FETCHER.retrieveHTML(pageUrl);

        if (fetchResult.document() != null)
        {
            crawlPage(fetchResult, pageUrl, allowedHost);
        }
        else
        {
            List<String> listOfLinks = new ArrayList<String>();
            PageResult pageResult = new PageResult(pageUrl, listOfLinks, fetchResult.outcome(),
                    fetchResult.statusCode(), Instant.now());
            COMPLETED_RESULTS.add(pageResult);
        }
    }

    private static void crawlPage(FetchResult fetchResult, String pageUrl, String allowedHost)
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
}
