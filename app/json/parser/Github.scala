package json.parser

import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import json.Skimbo
import services.auth.providers.GitHub
import org.joda.time.format.DateTimeFormat

case class GithubWallMessage(
  id: String,
  actorLogin: String,
  typeGithub: String,
  fork: Option[GithubForkeEvent],
  head: Option[String],
  push: Option[Seq[GithubPushEvent]],
  createdAt: DateTime,
  avatarUser: Option[String],
  repoName: String,
  issues: Option[GithubIssuesEvent],
  issueComment: Option[GithubIssueCommentEvent],
  download: Option[GithubDownloadEvent],
  refType:Option[String],
  refName:Option[String])

case class GithubForkeEvent(
  url: Option[String],
  repoName: String)

case class GithubPushEvent(
  message: String,
  url: String,
  name: String)

case class GithubDownloadEvent(
  name: String,
  description: String,
  url: String)
  
case class GithubIssuesEvent(
  title: String,
  state: String,
  htmlUrl: String,
  body: String)
  
case class GithubIssueCommentEvent(
  id: Int,
  body: String)

object GithubWallParser extends GenericParser {

  override def asSkimbo(json: JsValue): Option[Skimbo] = {
    Json.fromJson[GithubWallMessage](json).fold(
      error => logParseError(json, error, "ViadeoWallMessage"),
      e => Some(Skimbo(
        e.actorLogin,
        e.actorLogin,
        buildMsg(e),
        e.createdAt,
        Nil,
        -1,
        buildLink(e),
        e.createdAt.toString(GithubWallMessage.datePattern),
        e.avatarUser,
        GitHub)))
  }

  def buildMsg(e: GithubWallMessage) = {
    e.typeGithub match {
      case "ForkEvent" => "Fork of " + e.fork.get.repoName
      case "PushEvent" => "Push on " + e.repoName + ": " + e.push.get.head.message
      case "IssuesEvent" => "Issue [" + e.issues.get.title + "] > " + e.issues.get.state + ": " + e.issues.get.body
      case "IssueCommentEvent" => "Issue [" + e.issues.get.title + "] > " + e.issueComment.get.body
      case "DeleteEvent" => "Delete " + e.refType.get + " " + e.refName.get
      case "CreateEvent" => "Create " + e.refType.get + " " + e.refName.get
      case "DownloadEvent" =>
        if (!e.download.get.description.isEmpty()) {
          "New download (" + e.download.get.description + ") : " + e.download.get.name
        } else {
          "New download: " + e.download.get.name
        }
      case _ => "TODO type on " + e.repoName + " : " + e.typeGithub
    }
  }

  val gitPushUrl = "https://github.com/%s/commit/%s"
  val gitRepoUrl = "https://github.com/%s"

  def buildLink(e: GithubWallMessage) = {
    e.typeGithub match {
      case "ForkEvent"          => e.fork.get.url
      case "PushEvent"          => Some(gitPushUrl.format(e.repoName, e.head.get))
      case "DownloadEvent"      => Some(e.download.get.url)
      case "IssuesEvent"        => Some(e.issues.get.htmlUrl)
      case "IssueCommentEvent"  => Some(e.issues.get.htmlUrl + "#issuecomment-" + e.issueComment.get.id)
      case "DeleteEvent"        => Some(gitRepoUrl.format(e.repoName))
      case "CreateEvent"        => Some(gitRepoUrl.format(e.repoName))
      case _ => None
    }
  }
  
}

object GithubForkeEvent {
  implicit val githubReader: Reads[GithubForkeEvent] = (
    (__ \ "payload" \ "forkee" \ "html_url").readOpt[String] and
    (__ \ "repo" \ "name").read[String])(GithubForkeEvent.apply _)
}

object GithubPushEvent {
  implicit val githubReader: Reads[GithubPushEvent] = (
    (__ \ "message").read[String] and
    (__ \ "url").read[String] and
    (__ \ "author" \ "name").read[String])(GithubPushEvent.apply _)
}

object GithubDownloadEvent {
  implicit val githubReader: Reads[GithubDownloadEvent] = (
    (__ \ "name").read[String] and
    (__ \ "description").read[String] and
    (__ \ "html_url").read[String])(GithubDownloadEvent.apply _)
}

object GithubIssuesEvent {
  implicit val githubReader: Reads[GithubIssuesEvent] = (
    (__ \ "title").read[String] and
    (__ \ "state").read[String] and
    (__ \ "html_url").read[String] and
    (__ \ "body").read[String])(GithubIssuesEvent.apply _)
}

object GithubIssueCommentEvent {
  implicit val githubReader: Reads[GithubIssueCommentEvent] = (
    (__ \ "id").read[Int] and
    (__ \ "body").read[String])(GithubIssueCommentEvent.apply _)
}

object GithubWallMessage {
  
  val datePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'"
  
  implicit val githubReader: Reads[GithubWallMessage] = (
    (__ \ "id").read[String] and
    (__ \ "actor" \ "login").read[String] and
    (__ \ "type").read[String] and
    (__).readOpt[GithubForkeEvent] and
    (__ \ "payload" \ "head").readOpt[String] and
    (__ \ "payload" \ "commits").readOpt[List[GithubPushEvent]] and
    (__ \ "created_at").read[DateTime](Reads.jodaDateReads(datePattern)) and
    (__ \ "actor" \ "avatar_url").readOpt[String] and
    (__ \ "repo" \ "name").read[String] and
    (__ \ "payload" \ "issue").readOpt[GithubIssuesEvent] and
    (__ \ "payload" \ "comment").readOpt[GithubIssueCommentEvent] and
    (__ \ "payload" \ "download").readOpt[GithubDownloadEvent] and
    (__ \ "payload" \ "ref_type").readOpt[String] and
    (__ \ "payload" \ "ref").readOpt[String])(GithubWallMessage.apply _)
}