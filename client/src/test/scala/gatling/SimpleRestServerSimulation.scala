package gatling

import io.gatling.core.Predef.*
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef.*
import org.HdrHistogram.ConcurrentHistogram

import scala.concurrent.duration.DurationInt

class SimpleRestServerSimulation extends Simulation {

  import SimpleRestServerSimulation.*

  private val restHttpProtocol = http
    .baseUrl(config.restServerUri)
    .disableUrlEncoding
    .disableCaching

  def helloRequests(name: String): ChainBuilder = during(60.seconds)(pace(1000.millis).exec(
    exec(http(name + " /ts").get("/ts").check(
      bodyString.transform { ts =>
        hist.recordValue(Math.max(System.currentTimeMillis() - ts.toLong, 0))
      }
    )),
    exec(http(name + " /ts-blocking").get("/ts-blocking").check(
      bodyString.transform { ts =>
        hist.recordValue(Math.max(System.currentTimeMillis() - ts.toLong, 0))
      }
    ))
  ))

  private val warmup = scenario("REST warmup")
    .exec(helloRequests("Warmup GET"))
    .exec(pause(3.seconds)) // waiting for closing of all connections before measurement
    .exec({
      session =>
        hist.reset()
        session
    })
    .inject(config.injectionPolicy)

  private val measurement = scenario("REST measurement")
    .exec(helloRequests("Measurement GET"))
    .inject(config.injectionPolicy)

  setUp(
    warmup.andThen(measurement)
  ).protocols(restHttpProtocol)
}

object SimpleRestServerSimulation {
  private val hist = new ConcurrentHistogram(1L, 10000L, 3)

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit =
      hist.outputPercentileDistribution(System.out, 1.0)
  })

  object config {
    val numberOfUsers = 1000

    val restServerUri = "http://172.16.255.3:8888"

    val injectionPolicy: OpenInjectionStep = rampUsers(numberOfUsers).during(30.seconds)
  }
}
