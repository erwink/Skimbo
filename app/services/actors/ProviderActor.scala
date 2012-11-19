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
import model.parser.GenericParser
import model.Skimbo
import model.command.TokenInvalid
import model.command.Command
import play.api.libs.json.JsString
import models.user.Column
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsArray

sealed case class Ping(idUser: String)
sealed case class Dead(idUser: String)
sealed case class DeadColumn(idUser: String, columnTitle: String)

object ProviderActor {

  val system: ActorSystem = ActorSystem("providers");

  def create(channel: Concurrent.Channel[JsValue], userId: String, column: Column)(implicit request: RequestHeader) {
    column.unifiedRequests.foreach { unifiedRequest =>
      val endpoint = for (
        provider <- Endpoints.getProvider(unifiedRequest.service);
        url <- Endpoints.genererUrl(unifiedRequest.service, unifiedRequest.args.getOrElse(Map.empty), None);
        time <- Endpoints.getConfig(unifiedRequest.service).map(_.delay);
        parser <- Endpoints.getConfig(unifiedRequest.service).map(_.parser)
      ) yield (provider, url, time, parser)

      endpoint match {
        case Some((provider, url, time, parser)) => {
          Logger.info("Provider : " + provider.name + " every " + time + " seconds")
          val actor = system.actorOf(Props(
            new ProviderActor(channel, provider, url, time, userId, false, parser, column)))
          system.eventStream.subscribe(actor, classOf[Dead])
          system.eventStream.subscribe(actor, classOf[DeadColumn])
          system.eventStream.subscribe(actor, classOf[Ping])
        }
        case _ => Logger.error("Provider or Url not found for " + unifiedRequest.service + " :: args = " + unifiedRequest.args)
      }

    }
  }

  def ping(userId: String) = {
    system.eventStream.publish(Ping(userId))
  }

  def killActorsForUser(userId: String) = {
    system.eventStream.publish(Dead(userId))
  }

  def killActorsForUserAndColumn(userId: String, columnTitle: String) = {
    system.eventStream.publish(DeadColumn(userId, columnTitle))
  }

}

class ProviderActor(channel: Concurrent.Channel[JsValue],
  provider: GenericProvider, url: String, delay: Int,
  idUser: String, longPolling: Boolean,
  parser: Option[GenericParser[_]] = None, column: Column)(implicit request: RequestHeader) extends Actor {

  val log = Logger(ProviderActor.getClass())

  val scheduler = Akka.system.scheduler.schedule(0 second, delay second) {
    self ! ReceiveTimeout
  }

  def receive = {
    case ReceiveTimeout => {
      if (longPolling) {
        scheduler.cancel() //need ping to call provider
      }
      if (provider.hasToken(request) && parser.isDefined) {
        log.info("Fetch provider " + provider.name)
        provider.fetch(url).get.map(response => {
          val messages = Enumerator.enumerate(parser.get.cut(provider.resultAsJson(response)))
          val ite = Iteratee.foreach { jsonMsg:JsValue =>
            val msg = Json.obj(
              "column" -> column.title,
              "msg" -> parser.get.transform(jsonMsg))
            //log.info("Messages : "+msg)
            channel.push(Json.toJson(Command("msg", Some(msg))))
          }
          messages(ite).onComplete { e =>
            log.info("ite finish")
            e 
          }
        })
      } else if(!provider.hasToken(request)) {
        channel.push(Json.toJson(TokenInvalid(provider.name)))
        self ! Dead(idUser)
      } else {
        log.error("Provider " + provider.name + " havn't parser for " + url)
        channel.push(Json.toJson(Command("error", Some(JsString("provider havn't parser")))))
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
    case DeadColumn(idUser: String, columnTitle: String) => {
      if (columnTitle == column.title) {
        self ! Dead(idUser)
      }
    }
    case e: Exception => throw new UnexpectedException(Some("Incorrect message receive"), Some(e))
  }

}