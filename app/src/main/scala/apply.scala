package giter8

import org.eclipse.jgit.transport._
  
trait Apply { self: Giter8 =>
  import java.io.File
  import org.apache.commons.io.FileUtils
  import org.eclipse.jgit.api._
  import scala.util.control.Exception.{allCatch,catching}

  private val tempdir =
    new File(FileUtils.getTempDirectory, "giter8-" + System.nanoTime)

  /** Clean temporary directory used for git cloning */
  def cleanup() {
    if (tempdir.exists)
      FileUtils.forceDelete(tempdir)
  }

  val GitHub = """^([^\s/]+)/([^\s/]+?)(?:\.g8)?$""".r
  val Local = """^file://(\S+)$""".r

  object GitUrl {
    val NativeUrl = "^(git[@|://].*)$".r
    val HttpsUrl = "^(https://.*)$".r
    val HttpUrl = "^(http://.*)$".r

    def unapplySeq(s: Any): Option[List[String]] =
      NativeUrl.unapplySeq(s) orElse
      HttpsUrl.unapplySeq(s) orElse
      HttpUrl.unapplySeq(s)
  }

  def search(config: Config): Either[String, String] = {
    val prettyPrinter = (repo: GithubRepo) =>
      "%s \n\t %s\n" format (repo.name, repo.description)

    val repos = GithubRepo.search(config.repo)

    Right(repos.map(prettyPrinter).mkString(""))
  }

  def inspect(config: Config,
              arguments: Seq[String]): Either[String, String] = {
    config.repo match {
      case Local(path) =>
        val tmpl = config.branch.map { _ =>
          clone(path, config)
        }.getOrElse(copy(path))
        tmpl.right.flatMap { t =>
          G8Helpers.applyTemplate(t, new File("."), arguments, config.forceOverwrite)
        }
      case GitUrl(uri) =>
        val tmpl = clone(uri, config)
        tmpl.right.flatMap { t =>
          G8Helpers.applyTemplate(t,
            new File("."),
            arguments,
            config.forceOverwrite
          )
        }
      case GitHub(user, proj) =>
        try {
          val publicConfig = config.copy(
            repo = "git://github.com/%s/%s.g8.git".format(user, proj)
          )
          inspect(publicConfig, arguments)
        } catch {
          case _: org.eclipse.jgit.api.errors.JGitInternalException =>
            // assume it was an access failure, try with ssh
            // after cleaning the clone directory
            val privateConfig = config.copy(
              repo = "git@github.com:%s/%s.g8.git".format(user, proj)
            )
            cleanup()
            inspect(privateConfig, arguments)
        }
    }
  }

  def clone(repo: String, config: Config) = {
    import org.eclipse.jgit.api.ListBranchCommand.ListMode
    import org.eclipse.jgit.lib._
    import scala.collection.JavaConverters._

    val cmd = Git.cloneRepository()
      .setURI(repo)
      .setDirectory(tempdir)
      .setCredentialsProvider(ConsoleCredentialsProvider)

    val branchName = config.branch.map("refs/heads/" + _)

    branchName.foreach(cmd.setBranch)

    val g = cmd.call()

    val result = branchName.map { b =>
      if(g.getRepository.getFullBranch.equals(b))
        Right(tempdir)
      else
        Left("Branch not found: " + b)
    } orElse config.tag.map{ t =>
      if (g.getRepository.getTags.containsKey(t)) {
        g.checkout().setName(t).call()
        Right(tempdir)
      } else {
        Left(s"Tag not found: refs/tags/$t")
      }
    } getOrElse(Right(tempdir))

    g.getRepository.close()
    result
  }

  /** for file:// repositories with no named branch, just do a file copy */
  def copy(filename: String) = {
    val dir = new File(filename)
    if (!dir.isDirectory)
      Left("Not a readable directory: " + filename)
    else {
      FileUtils.copyDirectory(dir, tempdir)
      Right(tempdir)
    }
  }
}

object ConsoleCredentialsProvider extends CredentialsProvider {
  
  def isInteractive = true
  
  def supports(items: CredentialItem*) = true
  
  def get(uri: URIish, items: CredentialItem*) = {
    items foreach { 
      case i: CredentialItem.Username =>
        val username = System.console.readLine("%s: ", i.getPromptText)
        i.setValue(username)
        
      case i: CredentialItem.Password =>
        val password = System.console.readPassword("%s: ", i.getPromptText)
        i.setValueNoCopy(password)
        
      case i: CredentialItem.InformationalMessage =>
        System.console.printf("%s\n", i.getPromptText)
        
      case i: CredentialItem.YesNoType =>
        i.setValue(askYesNo(i.getPromptText))
    }
    true
  }
  
  @scala.annotation.tailrec
  def askYesNo(prompt: String): Boolean = {
    System.console.readLine("%s: ", prompt).trim.toLowerCase match {
      case "yes" => true
      case "no" => false
      case _ => askYesNo(prompt)
    }
  }
}
