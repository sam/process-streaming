import java.io.{FileInputStream, File, OutputStream, InputStream}
import java.net.{URL, URI}
import java.nio.file.{Paths, StandardCopyOption, Files}
import akka.actor.{Props, ActorSystem, Actor, ActorLogging}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.sys.process.{Process, ProcessIO}

object Helpers {
  import scala.language.reflectiveCalls

  type Closeable = {def close()}

  def using[T, S <: Closeable](source: S)(f: S => T): T = {
    try f(source) finally source.close()
  }

  case class PipeToStdIn(input: InputStream, progress: Long => Unit = _ => ()) extends (OutputStream => Unit) {

    val COPY_BUFFER_SIZE = 8192

    def apply(output: OutputStream): Unit = {

      using(input) { in =>
        using(output) { out =>
          val buffer = new Array[Byte](COPY_BUFFER_SIZE)

          Stream continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { length =>
            out.write(buffer, 0, length)
            progress(length)
          }
        }
      }
    }
  }

}

object Protocol {
  case class RequestClip(input: InputStream, start: BigDecimal, end: BigDecimal)

  case class Clip(stream: InputStream)

  case class ProcessReaper(process: Process, exitValue: Long)
}

class FFmpegActor extends Actor with ActorLogging {

  import Helpers._
  import Protocol._

  val ffmpeg = "ffmpeg" // Path to your ffmpeg binary. You can `brew install ffmpeg` if you don't have one.

  def receive = {
    case RequestClip(input, start, end) =>
  
      log.debug("clip requested at start {} and end {}", start, end)

      // ffmpeg -threads 2 -i sample.mp4 -y -c:v copy -c:a copy -ss 4 -to 12 -f mp4 -movflags frag_keyframe+empty_moov clip.mp4
      val clipCommand = s"$ffmpeg -threads 2 -i pipe:0 -y -c:v copy -c:a copy -ss $start -to $end -f mp4 -movflags frag_keyframe+empty_moov pipe:1"

      val stdout: InputStream => Unit = { stream =>
        log.debug("forwarding stream to sender")
        sender() ! Clip(stream)
      }
      
      val stderr: InputStream => Unit = _ => ()
    
      val process = Process(clipCommand).run(new ProcessIO(PipeToStdIn(input, log.debug("wrote {} bytes to stdin", _)), stdout, stderr))
    
      log.debug("executed command: {}", clipCommand)
    
      self ! ProcessReaper(process, process.exitValue) // Blocks until exit.
    
    case ProcessReaper(process, exitValue) =>
      log.debug("exit ffmpeg clipping process with exit code of {}", exitValue)
  }
}

object Main extends App {

  val system = ActorSystem("example", ConfigFactory.load())
  import system.dispatcher

  val ffmpeg = system.actorOf(Props[FFmpegActor])

  import akka.pattern.ask
  import scala.concurrent.duration._
  import Protocol._

  implicit val timeout = Timeout(30 seconds)

  import scala.sys.process._

  // Download a sample video:
  val sample = new File("sample.mp4")
  if(!sample.exists()) new URL("http://www.sample-videos.com/video/mp4/720/big_buck_bunny_720p_5mb.mp4") #> sample !

  // Request a clip starting at 4 seconds, through to 12 second mark.
  val request = ffmpeg ? RequestClip(new FileInputStream(sample), start = 4, end = 12)

  // Write the resulting InputStream to a file.
  // It should be about ~526,890 bytes, NOT ~1243!!!
  val result = request.mapTo[Clip] map {
    case Clip(output) =>
      Files.copy(output, Paths.get("clip.mp4"), StandardCopyOption.REPLACE_EXISTING)
  }

  Await.result(result, 30 seconds)

  system.terminate()
}
