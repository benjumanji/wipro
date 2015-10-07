package io.artfuldodge.wipro

import java.io.{File, BufferedWriter, FileWriter}
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  ConcurrentSkipListSet,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.util.Try
import scala.util.control.Breaks

import com.twitter.finagle.Httpx
import com.twitter.finagle.httpx.{Request, RequestBuilder, Response}
import com.twitter.util.Await
import org.jsoup.Jsoup

object Utils {
  def hashFragment(x: String): Boolean = x.startsWith("/#") || x.startsWith("#")
}

object Main {

  val WiproHostPort = "wiprodigital.com:80"
  val WiproHomeUrl = "https://wiprodigital.com"

  val client = Httpx.newService(WiproHostPort)

  // We'll track what links have been seen
  val seen = new ConcurrentSkipListSet[String]
  // and what remains to be crawled
  val work = new ConcurrentLinkedQueue[String]

  // track outstanding requests
  val count = new AtomicInteger

  /**
   * Given a url, fetch it, follow / process
   */
  def processRequest(request: Request) {
    count.incrementAndGet

    val responding = client(request)
      .onFailure(ex => println(ex.getMessage))

    val processing = responding.map { resp =>
      resp.statusCode match {
        case 301 => resp.location.foreach(work.add)
        case 200 => processResponse(resp)
        // this could be better, with more time.
        case _   => println(s"I don't know what to do with ${resp.statusCode}")
      }
    }

    processing.respond { _ => count.decrementAndGet }
  }

  /**
   * Process the response.
   */
  def processResponse(response: Response) {
    val doc = Jsoup.parse(response.contentString)
    val anchors = doc.select("a[href]")

    // we only want links that we haven't seen already,
    // and that aren't mailto links
    // and that aren't pure hash fragments.
    val links = anchors
      .map(_.attr("href"))
      .filterNot(x => seen.contains(x) || Utils.hashFragment(x) || x.startsWith("mailto:"))

    links.foreach(seen.add)

    // we only want to crawl inside the wiprodigital domain.
    val crawl = links
      .map(com.netaporter.uri.Uri.parse)
      .filter(_.host.map(_.endsWith("wiprodigital.com")).getOrElse(true))
      .map(_.toString)
      .toSet

    crawl.foreach(work.add)
  }

  /**
   * Crawl all the things
   */
  def main(args: Array[String]) {

    seen.add(WiproHomeUrl)
    work.add(WiproHomeUrl)

    val bucket = org.isomorphism.util.TokenBuckets.builder()
      .withCapacity(3)
      .withFixedIntervalRefillStrategy(3, 1, TimeUnit.SECONDS)
      .build()

    val loop = new Breaks

    loop.breakable {
      while(true) {

        val url = work.poll

        println(url)
        if (url == null) {
          // we are only creating work on one thread, therefore if there
          // are no inflight requests and the queue is empty we must be done.
          if (count.get == 0 && work.isEmpty) loop.break

          // if we are here there is will be work... eventually.
          Thread.sleep(1000)
        } else {
          // this will block if we running too fast.
          bucket.consume()
          // sure, we could log why we failed to build the url, but we know why:
          // it was malformed. Could we have a log of crappy urls? That might be
          // helpful. Exercise left to the reader.
          Try(RequestBuilder().url(url).buildGet).foreach(processRequest)
        }
      }
    }

    // on exit all the links we have seen constitute
    // the set we wanted to record to dump them to file.
    val file = new File("links.txt")
    val bw = new BufferedWriter(new FileWriter(file))
    seen.foreach(x => bw.write(s"$x\n"))
    bw.close

  }
}
