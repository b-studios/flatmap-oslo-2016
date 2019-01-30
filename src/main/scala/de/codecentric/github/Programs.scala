package de.codecentric.github

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats._
import cats.`~>`
import cats.std.future._
import cats.std.list._
import cats.std.set._
import cats.syntax.traverse._
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

trait ApplicativePrograms {
  import GitHubDsl._
  def getUsers(logins: List[UserLogin]
  ): GitHubApplicative[List[User]] =
    logins.traverseU(getUser)

  val scalaIssues: GitHubApplicative[List[Issue]] =
    List("scala","scala-dev","slip","scala-lang").
      traverseU(repo =>
        listIssues(Owner("scala"),Repo(repo))).
      map(_.flatten)

  def extractLogins(p: GitHubApplicative[_]): Set[UserLogin] = {
    import GitHubInterp._
    p.analyze(requestedLogins)
  }

  def precompute[A,F[_]:Applicative](
    p: GitHubApplicative[A],
    interp: GitHub ~> F
  ): F[Map[UserLogin,User]] = {
    val userLogins = extractLogins(p).toList

    val fetched: F[List[User]] =
      userLogins.traverseU(getUser).foldMap(interp)

    Functor[F].map(fetched)(userLogins.zip(_).toMap)
  }
}

trait Programs {
  import GitHubDsl._

  def allUsers(
    owner: Owner,
    repo: Repo
  ): GitHubMonadic[List[(Issue,List[(Comment,User)])]] = for {
    issues <- listIssuesMonad(owner,repo)

    issueComments <- issues.traverseU(issue =>
      getCommentsMonad(owner,repo,issue).map((issue,_)))

    users <- issueComments.traverseU { case (issue,comments) =>
      comments.traverseU(comment =>
        getUserMonad(comment.user).map((comment,_))).map((issue,_))
    }
  } yield users

  def allUsersM(owner: Owner, repo: Repo): GitHubBoth[List[(Issue,List[(Comment,User)])]] = for {

    issues <- embed { listIssues(owner,repo) }

    issueComments <- embed {
      issues.traverseU(issue =>
        getComments(owner,repo,issue).map((issue,_)))
    }

    users <- embed {
      issueComments.traverseU { case (issue,comments) =>
        comments.traverseU(comment =>
          getUser(comment.user).map((comment,_))).map((issue,_))
      }
    }
  } yield users
}

object Webclient {
  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global // don't do this
  import GitHubDsl._

  private def withClient[A](f: Client => A): A = {
    implicit val sys: ActorSystem = ActorSystem(s"github-run-${util.Random.nextInt.abs}")
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val client: Client = Client.ahcws

    try {
      f(client)
    } finally {
      client.close()
      mat.shutdown()
      sys.terminate()
    }
  }

  val timeOut: FiniteDuration = 5.minutes

  import GitHubInterp._

  def applicative[A](p: GitHubApplicative[A]): A =
    withClient { client =>
      Await.result(p.foldMap(step(client)), 5.minutes)
    }

  def monadic[A](p: GitHubMonadic[A]): A =
    withClient { client =>
      Await.result(p.foldMap(step(client)), 5.minutes)
    }

  def both[A](p: GitHubBoth[A]): A =
    withClient { client =>
      Await.result(p.foldMap(stepApOpt(client)), 5.minutes)
    }
}

object MonadicDsl extends Programs {
  def main(args: Array[String]): Unit =
    println(Webclient.monadic(allUsers(Owner("scala"), Repo("scala"))))
}

object ApplicativeDsl extends ApplicativePrograms {
  def main(args: Array[String]): Unit =
    println(Webclient.applicative(scalaIssues))
}

object MixedDsl extends Programs {
  def main(args: Array[String]): Unit =
    println(Webclient.both(allUsersM(Owner("scala"),Repo("scala"))))
}
