package services.actors;

import akka.actor._
import akka.util.duration.intToDurationInt
import play.api.libs.concurrent.futureToPlayPromise
import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc.RequestHeader
import play.libs.Akka
import play.api.PlayException
import play.api.UnexpectedException
import play.api.libs.concurrent.execution.defaultContext
import play.api.Logger
import services.endpoints.Endpoints
import services.endpoints.JsonRequest._
import services.auth.GenericProvider
import services.auth.providers._

sealed case class Ping(idUser: String)
sealed case class Dead(idUser: String = "")

object ProviderActor {

  val system: ActorSystem = ActorSystem("providers");

  def create(userId: String, unifiedRequests: Seq[UnifiedRequest])(implicit request: RequestHeader): (Enumerator[JsValue], Concurrent.Channel[JsValue]) = {
    val (rawStream, channel) = Concurrent.broadcast[JsValue]
    create(channel, userId, unifiedRequests)
    (rawStream, channel)
  }

  def create(channel: Concurrent.Channel[JsValue], userId: String, unifiedRequests: Seq[UnifiedRequest])(implicit request: RequestHeader) {
    unifiedRequests.foreach { unifiedRequest: UnifiedRequest =>
      val provider = Endpoints.getProvider(unifiedRequest.service)
      val url = Endpoints.genererUrl(unifiedRequest.service, unifiedRequest.args.getOrElse(Map.empty), None)
      val time = Endpoints.getConfig(unifiedRequest.service).map(_.delay)
      if (provider.isDefined && url.isDefined && time.isDefined) {
        Logger.info("Provider : " + provider.get.name + " every " + time.get + " seconds")
        val actor = system.actorOf(Props(new ProviderActor(channel, provider.get, url.get, time.get, userId, false)))
        system.eventStream.subscribe(actor, classOf[Dead])
        system.eventStream.subscribe(actor, classOf[Ping])
      } else {
        Logger.error("Provider or Url not found for " + unifiedRequest.service + " :: args = " + unifiedRequest.args)
      }
    }
  }
  
  val endpoints = Map[GenericProvider, String](
      (Twitter ->"http://dev.studio-dev.fr/test-ws-json.php?nom=twitter"),
      (GitHub -> "http://dev.studio-dev.fr/test-ws-json.php?nom=github"),
      (Facebook -> "http://dev.studio-dev.fr/test-ws-json.php?nom=facebook"),
      (GooglePlus -> "http://dev.studio-dev.fr/test-ws-json.php?nom=googlePlus"),
      (LinkedIn -> "http://dev.studio-dev.fr/test-ws-json.php?nom=linkedIn"),
      (Scoopit -> "http://dev.studio-dev.fr/test-ws-json.php?nom=scoopit"),
      (StackExchange -> "http://dev.studio-dev.fr/test-ws-json.php?nom=stackExchange"),
      (Trello -> "http://dev.studio-dev.fr/test-ws-json.php?nom=trello"),
      (Viadeo -> "http://dev.studio-dev.fr/test-ws-json.php?nom=viadeo"))
  def launchAll(channel: Concurrent.Channel[JsValue], userId: String)(implicit request:RequestHeader) = {
    endpoints.foreach { endpoint =>
      val actor = system.actorOf(Props(new ProviderActor(channel, endpoint._1, endpoint._2, 6, userId, false)))
      system.eventStream.subscribe(actor, classOf[Dead])
      system.eventStream.subscribe(actor, classOf[Ping])
    }
  }

  def ping(userId: String) = {
    system.eventStream.publish(Ping(userId))
  }

  def killActorsForUser(userId: String) = {
    system.eventStream.publish(Dead(userId))
  }

}

class ProviderActor(channel: Concurrent.Channel[JsValue],
  provider: GenericProvider, url: String, delay: Int,
  idUser: String, longPolling: Boolean)(implicit request: RequestHeader) extends Actor {

  val scheduler = Akka.system.scheduler.schedule(0 second, delay second) {
    self ! ReceiveTimeout
  }

  def receive = {
    case ReceiveTimeout => {
      if (longPolling) {
        scheduler.cancel() //need ping to call provider
      }
      if (provider.hasToken(request)) {
        provider.fetch(url).get.map(response => channel.push(response.json))
      } else {
        self ! Dead(idUser)
      }
    }
    case Ping(id) => {
      if (id == idUser) {
        Akka.system.scheduler.scheduleOnce(delay second) {
          self ! ReceiveTimeout
        }
      }
    }
    case Dead(id) => {
      if (id == idUser) {
        scheduler.cancel()
        context.stop(self)
      }
    }

    case e: Exception => throw new UnexpectedException(Some("Incorrect message receive"), Some(e))
  }

}