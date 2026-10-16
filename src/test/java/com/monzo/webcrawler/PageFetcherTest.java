package com.monzo.webcrawler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fetching and link extraction for a single page, against a local HTTP fixture.
 */
class PageFetcherTest
{
    private final PageFetcher fetcher = new PageFetcher();
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

    @Nested
    class LinkExtraction
    {
        @Test
        void resolvesRelativeLinksAgainstThePageUrl()
        {
            server.page("/dir/page.html", """
                    <a href="other.html">sibling</a>
                    <a href="../up.html">parent directory</a>
                    <a href="/abs.html">absolute path</a>
                    <a href="sub/deeper.html">child directory</a>
                    """);

            PageResult result = fetcher.retrieveHTML(server.url("/dir/page.html"));

            assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.SUCCEEDED);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.links()).containsExactly(
                    server.url("/dir/other.html"),
                    server.url("/up.html"),
                    server.url("/abs.html"),
                    server.url("/dir/sub/deeper.html"));
        }

        @Test
        void stripsFragmentsButKeepsQueryStrings()
        {
            server.page("/dir/page.html", """
                    <a href="item?id=5&amp;sort=asc#details">query and fragment</a>
                    <a href="?y=2">query only, same path</a>
                    <a href="#top">fragment only, same page</a>
                    <a href="/search?q=a%20b">encoded query</a>
                    """);

            PageResult result = fetcher.retrieveHTML(server.url("/dir/page.html?x=1"));

            assertThat(result.links()).containsExactly(
                    server.url("/dir/item?id=5&sort=asc"),
                    server.url("/dir/page.html?y=2"),
                    server.url("/dir/page.html?x=1"),
                    server.url("/search?q=a%20b"));
        }

        @Test
        void keepsExternalLinksAndDropsNonHttpSchemes()
        {
            server.page("/page.html", """
                    <a href="https://example.com/path?q=1#frag">external site</a>
                    <a href="mailto:someone@example.com">mail</a>
                    <a href="tel:+441234567890">phone</a>
                    <a href="javascript:void(0)">script</a>
                    <a href="ftp://example.com/file">ftp</a>
                    <a href="/local.html">local</a>
                    """);

            PageResult result = fetcher.retrieveHTML(server.url("/page.html"));

            assertThat(result.links()).containsExactly(
                    "https://example.com/path?q=1",
                    server.url("/local.html"));
        }

        @Test
        void reportsEveryOccurrenceOfARepeatedLink()
        {
            server.page("/page.html", """
                    <a href="/a">a</a>
                    <a href="/a">a again</a>
                    <a href="/a#section">a with fragment</a>
                    """);

            PageResult result = fetcher.retrieveHTML(server.url("/page.html"));

            assertThat(result.links()).containsExactly(server.url("/a"), server.url("/a"), server.url("/a"));
        }
    }

    @Nested
    class HttpFailures
    {
        @Test
        void recordsClientAndServerErrorsAsFailedWithTheirStatus()
        {
            server.respond("/missing", TestServer.status(404));
            server.respond("/broken", TestServer.status(500));

            PageResult missing = fetcher.retrieveHTML(server.url("/missing"));
            PageResult broken = fetcher.retrieveHTML(server.url("/broken"));

            assertThat(missing.crawlOutcome()).isEqualTo(CrawlOutcome.FAILED);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.links()).isEmpty();
            assertThat(broken.crawlOutcome()).isEqualTo(CrawlOutcome.FAILED);
            assertThat(broken.statusCode()).isEqualTo(500);
            assertThat(broken.links()).isEmpty();
        }

        @Test
        void recordsConnectionFailureAsFailedWithoutStatus() throws IOException
        {
            String unreachableUrl;
            try (TestServer closed = new TestServer())
            {
                unreachableUrl = closed.url("/");
            }

            PageResult result = fetcher.retrieveHTML(unreachableUrl);

            assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.FAILED);
            assertThat(result.statusCode()).isNull();
            assertThat(result.links()).isEmpty();
        }

        @Test
        void doesNotInspectNonHtmlResponses()
        {
            server.respond("/report.pdf", TestServer.raw(200, "application/pdf", "%PDF-1.4 <a href='/not-a-link'>x</a>"));

            PageResult result = fetcher.retrieveHTML(server.url("/report.pdf"));

            assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.FAILED);
            assertThat(result.links()).isEmpty();
        }
    }

    @Nested
    class Redirects
    {
        @Test
        void resolvesRelativeRedirectTargetAgainstTheRequestUrl()
        {
            server.respond("/dir/old", TestServer.redirect(301, "new"));

            PageResult result = fetcher.retrieveHTML(server.url("/dir/old"));

            assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.REDIRECTED);
            assertThat(result.statusCode()).isEqualTo(301);
            assertThat(result.links()).containsExactly(server.url("/dir/new"));
        }

        @Test
        void reportsAbsoluteRedirectTargetWithFragmentRemoved()
        {
            server.respond("/go", TestServer.redirect(302, "https://example.com/landing?ref=1#section"));

            PageResult result = fetcher.retrieveHTML(server.url("/go"));

            assertThat(result.crawlOutcome()).isEqualTo(CrawlOutcome.REDIRECTED);
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.links()).containsExactly("https://example.com/landing?ref=1");
        }

        @Test
        void redirectWithoutLocationHasNoTarget()
        {
            server.respond("/nowhere", TestServer.redirect(302, null));

            PageResult result = fetcher.retrieveHTML(server.url("/nowhere"));

            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.links()).isEmpty();
        }
    }
}
