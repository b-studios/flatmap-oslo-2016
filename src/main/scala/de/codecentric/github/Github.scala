package de.codecentric.github

import play.api.libs.json._
import cats.Applicative
import cats.`~>`
import cats.free.Free
import cats.free.FreeApplicative
import cats.std.future._
import cats.std.list._
import cats.std.set._
import cats.syntax.traverse._
import scala.concurrent.Future

sealed trait GitHub[A]
case class GetComments(owner: Owner, repo: Repo, issue: Issue)
    extends GitHub[List[Comment]]

case class GetUser(login: UserLogin)
    extends GitHub[User]

case class ListIssues(owner: Owner, repo: Repo)
    extends GitHub[List[Issue]]

case class GetComment(owner: Owner, repo: Repo, id: Int)
    extends GitHub[Comment]

object GitHub {
  private val ghApi = "https://api.github.com"

  implicit object commentsEndpoint extends Endpoint[GetComments]({
    case GetComments(Owner(owner), Repo(repo), Issue(number)) =>
      ghApi + s"/repos/$owner/$repo/issues/$number/comments"
  })

  implicit object userEndpoint extends Endpoint[GetUser]({
    case GetUser(UserLogin(login)) => ghApi + s"/users/$login"
  })

  implicit object listIssuesEndpoint extends Endpoint[ListIssues]({
    case ListIssues(Owner(owner),Repo(repo)) => ghApi + s"/repos/$owner/$repo/issues"
  })

  implicit object commentEndpoint extends Endpoint[GetComment]({
    case GetComment(Owner(owner),Repo(repo),id) => ghApi + s"/repos/$owner/$repo/issues/comments/$id"
  })
}

object GitHubDsl extends Serializable {
  type GitHubApplicative[A] = FreeApplicative[GitHub, A]
  type GitHubMonadic[A] = Free[GitHub, A]
  type GitHubBoth[A] = Free[GitHubApplicative, A]

  def getCommentsMonad(owner: Owner, repo: Repo, issue: Issue): GitHubMonadic[List[Comment]] =
    Free.liftF(GetComments(owner, repo, issue))

  def getComments(owner: Owner, repo: Repo, issue: Issue): GitHubApplicative[List[Comment]] =
    FreeApplicative.lift(GetComments(owner, repo, issue))

  def getUserMonad(login: UserLogin): GitHubMonadic[User] =
    Free.liftF(GetUser(login))

  def getUser(login: UserLogin): GitHubApplicative[User] =
    FreeApplicative.lift(GetUser(login))

  def listIssuesMonad(owner: Owner, repo: Repo): GitHubMonadic[List[Issue]] =
    Free.liftF(ListIssues(owner,repo))

  def listIssues(owner: Owner, repo: Repo): GitHubApplicative[List[Issue]] =
    FreeApplicative.lift(ListIssues(owner,repo))

  def embed[A](p: GitHubApplicative[A]): GitHubBoth[A] =
    Free.liftF[GitHubApplicative, A](p)
}

object GitHubInterp {
  import scala.concurrent.ExecutionContext.Implicits.global // don't do this
  import GitHubDsl._

  def step(client: Client): GitHub ~> Future =
    new (GitHub ~> Future) {
      def apply[A](fa: GitHub[A]): Future[A] = {
        fa match {
          case ffa@GetComments(_, _, _) => client.fetch(Endpoint(ffa)).map(parseComment)
          case ffa@GetUser(_) => client.fetch(Endpoint(ffa)).map(parseUser)
          case ffa@ListIssues(_,_) => client.fetch(Endpoint(ffa)).map(parseIssue)
          case ffa@GetComment(_,_,_) => client.fetch(Endpoint(ffa)).map(parseSingleComment)
        }
      }
    }

  def stepAp(client: Client): GitHubApplicative ~> Future =
    new (GitHubApplicative ~> Future) {
      def apply[A](fa: GitHubApplicative[A]): Future[A] = fa.monad.foldMap(step(client))
    }

  def stepApPar(client: Client): GitHubApplicative ~> Future =
    new (GitHubApplicative ~> Future) {
      def apply[A](fa: GitHubApplicative[A]): Future[A] = fa.foldMap(step(client))
    }

  def stepApOpt(client: Client): GitHubApplicative ~> Future =
    new (GitHubApplicative ~> Future) {
      def apply[A](fa: GitHubApplicative[A]): Future[A] = {
        val userLogins: List[UserLogin] =
          fa.analyze(requestedLogins).toList

        val fetched: Future[List[User]] =
          userLogins.traverse(u => getUser(u)).foldMap(step(client))

        val futureMapping: Future[Map[UserLogin,User]] =
          fetched.map(userLogins.zip(_).toMap)

        futureMapping.flatMap { mapping =>
          fa.foldMap(prefetchedUsers(mapping)(step(client)))
        }
      }
    }

  def naturalLogging[F[_]]: F ~> F = new (F ~> F) {
    def apply[A](fa: F[A]): F[A] = {
      println(fa)
      fa
    }
  }

  val requestedLogins: GitHub ~> λ[α=>Set[UserLogin]] = {
    new (GitHub ~> λ[α=>Set[UserLogin]]) {
      def apply[A](fa: GitHub[A]): Set[UserLogin] = fa match {
        case GetUser(u) => Set(u)
        case _ => Set.empty
      }
    }
  }

  val requestedIssues: GitHub ~> λ[α=>Map[(Owner,Repo),Int]] = {
    new (GitHub ~> λ[α=>Map[(Owner,Repo),Int]]) {
      def apply[A](fa: GitHub[A]): Map[(Owner,Repo),Int] = fa match {
        case GetComment(owner,repo,id) => Map((owner,repo) -> id)
        case _ => Map.empty
      }
    }
  }

  def prefetchedUsers[F[_]:Applicative](prefetched: Map[UserLogin,User])(interp: GitHub ~> F): GitHub ~> F =
    new (GitHub ~> F) {
      def apply[A](fa: GitHub[A]): F[A] = fa match {
        case ffa@GetUser(login) =>
          prefetched.get(login) match {
            case Some(user) =>
              Applicative[F].pure(user)
            case None =>
              interp(ffa)
          }
        case _ => interp(fa)
      }
    }

  private def parseComment(json: JsValue): List[Comment] = {
    val objs = json.validate[List[JsValue]].get
    objs.map { obj =>
      (for {
        url <- (obj \ "url").validate[String]
        body <- (obj \ "body").validate[String]
        login <- (obj \ "user" \ "login").validate[String]
      } yield Comment(Url(url),Body(body),UserLogin(login))).get
    }
  }

  private def parseSingleComment(obj: JsValue): Comment = {
    (for {
      url <- (obj \ "url").validate[String]
      body <- (obj \ "body").validate[String]
      login <- (obj \ "user" \ "login").validate[String]
    } yield Comment(Url(url),Body(body),UserLogin(login))).get
  }

  private def parseUser(json: JsValue): User = {
    (for {
      login <- (json \ "login").validate[String]
      name <- (json \ "name").validate[String] orElse (json \ "login").validate[String]
    } yield User(login,name)).asOpt.get
  }

  private def parseIssue(json: JsValue): List[Issue] = {
    val objs = json.validate[List[JsValue]].get
    objs.map(obj => (obj \ "number").validate[Int].map(Issue(_)).asOpt).flatten
  }
}
