Scraper
=======

This is a simple async scraper with no tests. It's the best I could manage in the guideline time. I thought it would be a nice thing to use an evented model, so we crawl the page looking for work, queuing fresh links, and popping them off a single worker thread. We limit our speed with a token bucket, and track inflight requests with an atomic counter. This keeps our code simple with all locking pushed down to lower level constructs.

There are no tests. Sue me.

To run `sbt run`, there will be a file links.txt produced with a list of links.

no attempt was made to chase non-anchor tagged content (img tags etc).
