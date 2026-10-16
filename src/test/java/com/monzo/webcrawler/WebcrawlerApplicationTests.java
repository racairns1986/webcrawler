package com.monzo.webcrawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Command-line entry point: argument validation and the printed report.
 */
@Timeout(20)
class WebcrawlerApplicationTests
{
    private final WebcrawlerApplication application = new WebcrawlerApplication();

    @Test
    void rejectsMissingArgument()
    {
        assertThatThrownBy(() -> application.run())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage");
    }

    @Test
    void rejectsMoreThanOneArgument()
    {
        assertThatThrownBy(() -> application.run("http://127.0.0.1/", "http://127.0.0.1/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUrlsThatAreNotHttpOrHttps()
    {
        assertThatThrownBy(() -> application.run("ftp://127.0.0.1/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> application.run("not a url")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printsEachVisitedPageWithItsLinksAndASummary() throws Exception
    {
        try (TestServer server = new TestServer())
        {
            server.page("/", "<a href='/about'>about</a> <a href='https://example.com/'>external</a>");
            server.page("/about", "<a href='/missing'>missing</a>");

            String output = runCapturingOutput(server.url("/"));

            assertThat(output.lines().toList()).contains(
                    "Visited: " + server.url("/"),
                    "  " + server.url("/about"),
                    "  https://example.com/",
                    "Visited: " + server.url("/about"),
                    "Attempted: " + server.url("/missing") + " | Outcome: FAILED | HTTP status: 404",
                    "Total URLs attempted: 3",
                    "Pages inspected successfully: 2",
                    "Pages not inspected: 1");
        }
    }

    private String runCapturingOutput(String seedUrl) throws Exception
    {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try
        {
            application.run(seedUrl);
        }
        finally
        {
            System.setOut(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }
}
