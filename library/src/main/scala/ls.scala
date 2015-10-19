package giter8

import scala.util.parsing.combinator._
import scala.concurrent._
import scala.concurrent.duration._

/**
 * Parse any ls.implcit.ly references in default properties. The latest
 * version may be requested by setting a property's value to
 * 
 *   ls(library, user, repo)
 *
 * The second two parameters are optional.
 */
object Ls extends JavaTokenParsers {
  def spec =
    "ls" ~> "(" ~> word ~ optElem ~ optElem <~ ")" ^^ {
      case library ~ user ~ repo => (library, user, repo)
    }
  def optElem = opt("," ~> word)
  /** Like ident but allow hyphens */
  def word = """[\w\-]+""".r

  def unapply(value: String) =
    Some(parse(spec, value)).filter { _.successful }.map { _.get }

  def lookup(rawDefaults: G8.OrderedProperties)
  : Either[String, G8.OrderedProperties] = {
    val lsDefaults = rawDefaults.view.collect {
      case (key, Ls(library, user, repo)) =>
        ls.DefaultClient {
          _.Handler.latest(library, user, repo)
        }.right.map { future =>
          Await.result(future, 1.minute).right.map(key -> _)
        }.joinRight
    }
    val initial: Either[String,G8.OrderedProperties] = Right(rawDefaults)
    (initial /: lsDefaults) { (accumEither, lsEither) =>
      for {
        cur <- accumEither.right
        ls <- lsEither.right
      } yield {
        // Find the match in the accumulator and replace it with the ls'd value
        val (inits, tail) = cur.span { case (k, _) => k != ls._1 }
        inits ++ (ls +: (tail.tail))
      }
    }.left.map { "Error retrieving ls version info: " + _ }
  }
}
