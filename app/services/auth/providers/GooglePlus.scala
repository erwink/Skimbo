package services.auth.providers

import play.api._
import play.api.mvc._
import services.auth._
import models.user.ProviderUser
import models.user.SkimboToken

object GooglePlus extends OAuth2Provider {

  override val name = "googleplus"
  override val namespace = "gp"
  override val method = Post
  override val permissionsSep = " "
  override val permissions = Seq(
    "https://www.googleapis.com/auth/userinfo.email", // View your email address
    "https://www.googleapis.com/auth/plus.me") // Know who you are on Google

  override def processToken(response: play.api.libs.ws.Response) =
    Token((response.json \ "access_token").asOpt[String], (response.json \ "expires_in").asOpt[Int])

  override def distantUserToSkimboUser(ident: String, response: play.api.libs.ws.Response)(implicit request: RequestHeader): Option[ProviderUser] = {
    try {
      val me = response.json // TODO : En faire un parser
      val id = (me \ "id").as[String]
      val username = (me \ "displayName").asOpt[String]
      val name = (me \ "name" \ "familyName").asOpt[String]
      val description = Some("")
      val profileImage = (me \ "image" \ "url").asOpt[String]
      Some(ProviderUser(
          id, 
          this.name, 
          Some(SkimboToken(getToken.get.token, None)), 
          username, 
          name, 
          description, 
          profileImage))
    } catch {
      case _:Throwable => {
        Logger.error("Error during fetching user details G+")
        None
      }
    }
  }
  
  override def isInvalidToken(response: play.api.libs.ws.Response): Boolean = response.status == 401

}

