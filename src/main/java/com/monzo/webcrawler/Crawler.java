package com.monzo.webcrawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/*Crawls every page on the seed URL's host.
An instance holds the state of exactly one crawl, each crawl starts clean.*/
public class Crawler
{
    private static final Logger LOG = LoggerFactory.getLogger(Crawler.class);

    private final String allowedHost;
    private final PageFetcher pageFetcher;
    private final int workerCount;
    private final Set<String> foundUrls = ConcurrentHashMap.newKeySet();
    private final Queue<String> queueToCrawl = new ConcurrentLinkedQueue<>();
    private final Queue<PageResult> completedResults = new ConcurrentLinkedQueue<>();

    public Crawler(String seedUrl, PageFetcher pageFetcher, int workerCount)
    {
        String normalizedSeed = seedUrl.split("#", 2)[0];
        String host = URI.create(normalizedSeed).getHost();

        if (host == null)
        {
            throw new IllegalArgumentException("Seed URL has no host: " + seedUrl);
        }

        this.allowedHost = host;
        this.pageFetcher = pageFetcher;
        this.workerCount = workerCount;

        foundUrls.add(normalizedSeed);
        queueToCrawl.add(normalizedSeed);
    }


    /*Crawls until no URL is queued and no worker is busy,
    then returns one result per URL attempted.*/
    public List<PageResult> crawl() throws InterruptedException, ExecutionException
    {
        try (ExecutorService workers = Executors.newFixedThreadPool(workerCount))
        {
            processQueuedPages(workers);
        }
        LOG.debug("Discovered URLs: {} | Completed results: {}", foundUrls.size(), completedResults.size());
        return List.copyOf(completedResults);
    }

    private void crawlPage(String pageUrl)
    {
        PageResult pageResult = pageFetcher.retrieveHTML(pageUrl);

        for (String url : pageResult.links())
        {
            String nextHost = URI.create(url).getHost();

            if (allowedHost.equalsIgnoreCase(nextHost) && foundUrls.add(url))
            {
                queueToCrawl.add(url);
            }
        }

        completedResults.add(pageResult);
    }

    private void processQueuedPages(ExecutorService workers) throws InterruptedException, ExecutionException
    {
        CompletionService<Void> completions = new ExecutorCompletionService<>(workers);

        int outstanding = 0;

        while (!queueToCrawl.isEmpty() || outstanding > 0)
        {
            // Use all workers.
            while (outstanding < workerCount)
            {
                String pageUrl = queueToCrawl.poll();

                if (pageUrl == null)
                {
                    break;
                }

                completions.submit(() -> crawlPage(pageUrl), null);

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
