package com.monzo.webcrawler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Whole-crawl behaviour against a local HTTP fixture. Every test builds a new
 * {@link Crawler}, so no state carries over between tests.
 */
@Timeout(20)
class CrawlerTest
{
    private static final int WORKERS = 4;

    private TestServer server;

    @BeforeEach
    void startServer() throws IOException
    {
        server = new TestServer();
    }

    @AfterEach
    void stopServer()
    {
        server.close();
    }

    @Test
    void followsOnlyLinksOnTheSeedHostButReportsExternalOnes() throws Exception
    {
        String aliasHostPage = server.aliasUrl("/alias-page");
        server.page("/", """
                <a href="/about">about</a>
                <a href="%s">same server, different host name</a>
                <a href="https://example.com/">other site</a>
                <a href="https://sub.example.com/x">subdomain of other site</a>
                """.formatted(aliasHostPage));
        server.page("/about", "<a href='/'>home</a>");
        server.page("/alias-page", "<a href='/should-not-be-found'>hidden</a>");

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(visitedUrls(results)).containsExactlyInAnyOrder(server.url("/"), server.url("/about"));
        assertThat(resultFor(results, server.url("/")).links()).containsExactly(
                server.url("/about"),
                aliasHostPage,
                "https://example.com/",
                "https://sub.example.com/x");
        assertThat(server.requests()).doesNotContain("/alias-page", "/should-not-be-found");
    }

    @Test
    void matchesTheHostCaseInsensitively() throws Exception
    {
        String upperCaseHostPage = "http://LOCALHOST:" + server.port() + "/upper";
        server.page("/", "<a href=\"" + upperCaseHostPage + "\">upper-case host</a>");
        server.page("/upper", "end");

        List<PageResult> results = crawlFrom(server.aliasUrl("/"));

        assertThat(visitedUrls(results)).containsExactlyInAnyOrder(server.aliasUrl("/"), upperCaseHostPage);
        assertThat(server.requestCount("/upper")).isEqualTo(1);
    }

    @Test
    void fetchesEachUrlOnceDespiteDuplicateLinksAndCycles() throws Exception
    {
        server.page("/", """
                <a href="/a">a</a>
                <a href="/a">a again</a>
                <a href="/a#section">a with fragment</a>
                <a href="/">self</a>
                """);
        server.page("/a", "<a href='/b'>b</a> <a href='/'>home</a>");
        server.page("/b", "<a href='/a'>a</a> <a href='/b'>self</a>");

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(visitedUrls(results)).containsExactlyInAnyOrder(server.url("/"), server.url("/a"), server.url("/b"));
        assertThat(server.requests()).containsExactlyInAnyOrder("/", "/a", "/b");
        assertThat(resultFor(results, server.url("/")).links()).containsExactly(
                server.url("/a"), server.url("/a"), server.url("/a"), server.url("/"));
    }

    @Test
    void fetchesAUrlOnceWhenSeveralWorkersDiscoverItAtTheSameTime() throws Exception
    {
        // Eight pages, fetched by four workers in parallel, all link to one shared
        // page, so several workers race to add it to the discovered set.
        StringBuilder hub = new StringBuilder();
        for (int i = 1; i <= 8; i++)
        {
            hub.append("<a href='/page-").append(i).append("'>").append(i).append("</a>");
            server.respond("/page-" + i, TestServer.html("<a href='/shared'>shared</a>").delayedBy(100));
        }
        server.page("/", hub.toString());
        server.page("/shared", "end");

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(results).hasSize(10);
        assertThat(server.requestCount("/shared")).isEqualTo(1);
    }

    @Test
    void neverHasMoreRequestsInFlightThanWorkers() throws Exception
    {
        StringBuilder hub = new StringBuilder();
        for (int i = 1; i <= 12; i++)
        {
            hub.append("<a href='/slow-").append(i).append("'>").append(i).append("</a>");
            server.respond("/slow-" + i, TestServer.html("end").delayedBy(200));
        }
        server.page("/", hub.toString());

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(results).hasSize(13);
        assertThat(server.maxConcurrentRequests()).isBetween(2, WORKERS);
    }

    @Test
    void followsSameHostRedirectsButNotExternalOnes() throws Exception
    {
        server.page("/", "<a href='/old'>old</a> <a href='/away'>away</a> <a href='/loop-1'>loop</a>");
        server.respond("/old", TestServer.redirect(301, "/new"));
        server.page("/new", "<a href='/new-only'>only reachable through the redirect</a>");
        server.page("/new-only", "end");
        server.respond("/away", TestServer.redirect(302, server.aliasUrl("/landing")));
        server.page("/landing", "must never be fetched");
        server.respond("/loop-1", TestServer.redirect(302, "/loop-2"));
        server.respond("/loop-2", TestServer.redirect(302, "/loop-1"));

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(visitedUrls(results)).contains(server.url("/new"), server.url("/new-only"));
        PageResult old = resultFor(results, server.url("/old"));
        assertThat(old.crawlOutcome()).isEqualTo(CrawlOutcome.REDIRECTED);
        assertThat(old.statusCode()).isEqualTo(301);
        assertThat(old.links()).containsExactly(server.url("/new"));
        assertThat(resultFor(results, server.url("/away")).links()).containsExactly(server.aliasUrl("/landing"));
        assertThat(server.requests()).doesNotContain("/landing");
        assertThat(server.requestCount("/loop-1")).isEqualTo(1);
        assertThat(server.requestCount("/loop-2")).isEqualTo(1);
    }

    @Test
    void recordsFailedPagesAndCarriesOn() throws Exception
    {
        server.page("/", "<a href='/missing'>missing</a> <a href='/ok'>ok</a>");
        server.page("/ok", "end");

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(visitedUrls(results)).containsExactlyInAnyOrder(server.url("/"), server.url("/missing"), server.url("/ok"));
        PageResult missing = resultFor(results, server.url("/missing"));
        assertThat(missing.crawlOutcome()).isEqualTo(CrawlOutcome.FAILED);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(resultFor(results, server.url("/ok")).crawlOutcome()).isEqualTo(CrawlOutcome.SUCCEEDED);
    }

    @Test
    void keepsRunningWhileTheQueueIsEmptyButAWorkerIsStillFetching() throws Exception
    {
        // After "/" completes the queue holds only "/slow". Once that is handed to a
        // worker the queue is empty and only the in-flight count keeps the loop alive,
        // until the slow page yields "/slower"; then the same again until that page
        // yields the two final pages.
        server.page("/", "<a href='/slow'>slow</a>");
        server.respond("/slow", TestServer.html("<a href='/slower'>slower</a>").delayedBy(400));
        server.respond("/slower", TestServer.html("<a href='/after-1'>1</a> <a href='/after-2'>2</a>").delayedBy(400));
        server.page("/after-1", "end");
        server.page("/after-2", "end");

        List<PageResult> results = crawlFrom(server.url("/"));

        assertThat(visitedUrls(results)).containsExactlyInAnyOrder(
                server.url("/"), server.url("/slow"), server.url("/slower"), server.url("/after-1"), server.url("/after-2"));
        assertThat(results).allSatisfy(result -> assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.SUCCEEDED));
    }

    @Test
    void stripsTheFragmentFromTheSeedAndVisitsItOnce() throws Exception
    {
        server.page("/", "<a href='/#top'>top</a> <a href='/'>home</a>");

        List<PageResult> results = crawlFrom(server.url("/#top"));

        assertThat(visitedUrls(results)).containsExactly(server.url("/"));
        assertThat(server.requestCount("/")).isEqualTo(1);
    }

    @Test
    void eachCrawlerStartsWithFreshState() throws Exception
    {
        server.page("/", "<a href='/a'>a</a>");
        server.page("/a", "end");

        List<PageResult> first = crawlFrom(server.url("/"));
        List<PageResult> second = crawlFrom(server.url("/"));

        assertThat(visitedUrls(first)).containsExactlyInAnyOrder(server.url("/"), server.url("/a"));
        assertThat(visitedUrls(second)).containsExactlyInAnyOrder(server.url("/"), server.url("/a"));
        assertThat(server.requestCount("/")).isEqualTo(2);
        assertThat(server.requestCount("/a")).isEqualTo(2);
    }

    private static List<PageResult> crawlFrom(String seedUrl) throws Exception
    {
        return new Crawler(seedUrl, new PageFetcher(), WORKERS).crawl();
    }

    private static List<String> visitedUrls(List<PageResult> results)
    {
        return results.stream().map(PageResult::url).toList();
    }

    private static PageResult resultFor(List<PageResult> results, String url)
    {
        return results.stream()
                .filter(result -> result.url().equals(url))
                .findFirst()
                .orElseGet(() -> fail("No result for " + url + " in " + visitedUrls(results)));
    }
}
