# webcrawler

A simple concurrent web crawler, written for the Monzo take-home exercise.

Given a starting URL, it visits every page it finds on the same host and prints
each URL visited together with the links found on that page. Links to other
hosts are listed but never followed, so a crawl of `https://crawlme.monzo.com/`
stays on `crawlme.monzo.com` and does not wander off to `monzo.com`,
`community.monzo.com` or any external site.

## Requirements

- JDK 25
- Maven 3.9 or later, or the bundled wrapper (`./mvnw` on macOS and Linux,
  `mvnw.cmd` on Windows), which downloads Maven on first use

## Build, test and run

Build the jar (this also runs the test suite):

```
mvn clean package
```

Run a crawl:

```
java -jar target/webcrawler-0.0.1-SNAPSHOT.jar https://crawlme.monzo.com/
```

The single argument must be an absolute `http` or `https` URL. Results are
printed to standard output once the crawl has finished, after the Spring Boot
start-up lines, so for a large site it is convenient to capture them:

```
java -jar target/webcrawler-0.0.1-SNAPSHOT.jar https://crawlme.monzo.com/ > crawl-output.txt
```

If you do this from Windows PowerShell 5.1, note that `>` writes UTF-16. Use
`| Out-File -Encoding utf8 crawl-output.txt` instead if you want a UTF-8 file.

Run only the tests:

```
mvn test
```

## Output

Successful pages come first, sorted by URL, each followed by every link found
on it in document order. Pages that were not inspected follow, with their
outcome and HTTP status, then a short summary.

```
Here are the successful results:
Visited: https://crawlme.monzo.com/
  https://crawlme.monzo.com/index.html
  https://crawlme.monzo.com/about.html
  ...
Visited: https://crawlme.monzo.com/contact.html
  https://crawlme.monzo.com/index.html
  https://www.instagram.com
  https://www.facebook.com
  ...
Here are the unsuccessful results:
Attempted: https://crawlme.monzo.com/old-page | Outcome: REDIRECTED | HTTP status: 301
  https://crawlme.monzo.com/new-page
Attempted: https://crawlme.monzo.com/missing | Outcome: FAILED | HTTP status: 404
Total URLs attempted: 42011
Pages inspected successfully: 42009
Pages not inspected: 2
```

Fetch problems are also logged at `WARN` level with the reason, and redirects at
`INFO`.

## Design

Four small classes in `com.monzo.webcrawler`:

- **`WebcrawlerApplication`** is the command-line entry point. It validates the
  argument, runs one `Crawler` with 20 workers and prints the report.
- **`Crawler`** holds the state of a single crawl: a queue of URLs still to
  fetch, a concurrent set of every URL discovered so far, and the completed
  results. Each crawl gets a fresh instance.
- **`PageFetcher`** fetches one URL with jsoup and turns the response into a
  `PageResult`: the outcome, the HTTP status and the list of links found.
- **`PageResult`** and **`CrawlOutcome`** are the immutable result record and
  its outcome enum.

### Crawl loop

The coordinator submits URLs from the queue to a fixed pool of 20 worker
threads through an `ExecutorCompletionService`, keeping at most 20 tasks in
flight. Each worker fetches its page, adds any same-host links it has not seen
before to the queue, and records the result. Because a worker queues new URLs
before its task completes, the coordinator can safely stop when the queue is
empty and no task is outstanding. An empty queue while a worker is still busy
does not end the crawl.

### Fetching and link extraction

Each page is requested with a 10-second timeout and with automatic redirect
following switched off, so the crawler itself decides what to follow. Every
`a[href]` is resolved to an absolute URL (jsoup honours `<base href>`), the
fragment is removed, and the result is kept if it is a valid `http` or `https`
URL. Query strings are preserved. `mailto:`, `tel:` and `javascript:` links are
dropped.

### Scope rule

A link is followed only if its host is exactly the seed URL's host, compared
case-insensitively. Starting from `crawlme.monzo.com`, links to `monzo.com`,
`community.monzo.com` and `www.crawlme.monzo.com` are all reported but not
fetched. The scheme is not part of the rule, so `http` and `https` pages on the
same host are both crawled.

### Redirects

A `3xx` response is recorded with the outcome `REDIRECTED`, and its resolved
`Location` is reported as the page's single link. From there it is treated like
any other link: followed if it is on the seed host and not seen before,
otherwise listed only. Redirect loops terminate because every URL is fetched at
most once.

### Outcomes

| Outcome | Meaning |
|---|---|
| `SUCCEEDED` | Page fetched and parsed; links extracted |
| `REDIRECTED` | `3xx` response; target reported, followed if same host |
| `TIMED_OUT` | Request timed out while connecting or reading the response (10-second limit) |
| `FAILED` | HTTP error, connection failure or non-HTML content |

## Known limitations and deliberate trade-offs

- **Canonicalisation is fragment-only.** `/` and `/index.html`, `http` and
  `https`, or a path with and without a trailing slash count as different pages.
- **Redirects appear under "unsuccessful".** The summary's "not inspected" count
  includes them, so a `301` followed by a `200` reports one of each.
- **Every `3xx` is treated as a redirect**, including `304 Not Modified`. A
  redirect without a usable `Location` is reported with no target.
- **Per-page link lists preserve duplicates** and document order.
- **Non-HTML resources** linked from pages (PDFs, images) are attempted and
  recorded as `FAILED`.
- **Host validation uses Apache Commons Validator.** Domain names with
  unrecognised top-level domains, such as `something.internal`, are rejected.
  `ALLOW_LOCAL_URLS` permits local hostnames such as `localhost`, which the
  tests use to verify that different hostnames are treated separately even
  when they reach the same server.
- **No `robots.txt` handling and no rate limiting.** Up to 20 concurrent requests are
  open to the target host at once.
- **Output is printed only after the crawl finishes**, and all results are held
  in memory. Fine for tens of thousands of pages, not millions.
- **An unexpected runtime exception in a worker aborts the crawl.** Expected
  failures (HTTP errors, timeouts, bad content types, unparseable URLs) are
  recorded per page and the crawl continues; anything else is treated as a
  programming error and surfaced immediately rather than hidden.
- **jsoup's default 2 MB body limit applies**, so links beyond that point in a
  very large page are not seen.
- **Worker count is fixed at 20.** A future improvement would be to make it
  configurable through `application.properties`, allowing users to adjust
  concurrency without changing the code.

## Tests

The suite runs against a local HTTP fixture (`TestServer`, built on the JDK's
own `HttpServer`) so every URL and response is controlled and every request
the crawler makes is recorded. No test touches the network beyond loopback.

- **`PageFetcherTest`**: relative, parent and absolute link resolution;
  fragment removal with query strings preserved; external links kept and
  non-HTTP schemes dropped; duplicate links reported; `404` and `500` as
  `FAILED` with status; connection refused as `FAILED` without status; non-HTML
  content not inspected; relative and absolute redirect targets; a redirect
  with no `Location`.
- **`CrawlerTest`**: exact-host filtering with external links retained in the
  report (the fixture is reachable under a second host name, which must not be
  followed); case-insensitive host matching; each URL fetched once despite
  duplicate links and cycles; one fetch when several workers discover the same
  URL at the same time; never more requests in flight than workers; same-host
  redirects followed and external ones not; failed pages recorded while the
  crawl continues; the crawl continuing while the queue is empty but a worker
  is still fetching; fragment stripped from the seed; fresh state per crawl.
- **`WebcrawlerApplicationTests`**: argument validation and the printed report,
  checked line by line against a small fixture site.

Not covered: the 10-second socket timeout, because the constant is fixed in
`PageFetcher`. The host-name alias tests rely on `localhost` resolving to IPv4
first, which is the JVM default.

## Observed run

Against `https://crawlme.monzo.com/` the crawler attempted 42,011 URLs, all
inspected successfully, in roughly three minutes with 20 workers. The only
external links found were to facebook.com, instagram.com and twitter.com, and
none of them were fetched.

## How this was built

The crawler itself (fetching, link extraction, the concurrent queue and the
host rule) was written by hand within the suggested time for the exercise. The
test suite, the extraction of the per-crawl state into the `Crawler` class and
this README were produced afterwards with AI tooling (Claude Code and Codex),
using a workflow that separates the roles of reviewer, orchestrator and
implementer so that no change is written and judged by the same agent. Every
change was reviewed by me, and the result was verified by running the full
suite and a complete crawl of the example site.
