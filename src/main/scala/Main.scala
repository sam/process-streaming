import java.io.{FileInputStream, File, OutputStream, InputStream}
import java.net.{URL, URI}
import java.nio.file.{Paths, StandardCopyOption, Files}
import akka.actor.{Props, ActorSystem, Actor, ActorLogging}
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{StreamConverters, FileIO, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.config.ConfigFactory
import scala.concurrent.{Future, Await}
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

  case class Clip(video: Source[ByteString, Future[IOResult]])

  case class ProcessReaper(process: Process, exitValue: Long)
}

class FFmpegActor extends Actor with ActorLogging {

  import Protocol._
  import scala.sys.process._

  val ffmpeg = "ffmpeg" // Path to your ffmpeg binary. You can `brew install ffmpeg` if you don't have one.

  def receive = {
    case RequestClip(input, start, end) =>

      val clipCommand = s"$ffmpeg -threads 2 -i pipe:0 -y -c:v copy -c:a copy -ss $start -to $end -f mp4 -movflags frag_keyframe+empty_moov pipe:1"

      val tmp = new File("clip.tmp")

      clipCommand #< input #> tmp !

      sender() ! Clip(FileIO.fromFile(tmp))
  }
}

object Main extends App {

  val system = ActorSystem("example", ConfigFactory.load())
  import system.dispatcher

  implicit val materializer = ActorMaterializer()(system)

  val ffmpeg = system.actorOf(Props[FFmpegActor])

  import akka.pattern.ask
  import scala.concurrent.duration._
  import Protocol._

  implicit val timeout = Timeout(30 seconds)

  // Download a sample video:
  val sample = new File("sample.mp4")
  if(!sample.exists()) {
    import scala.sys.process._
    val download = new File("download")
    new URL("http://www.sample-videos.com/video/mp4/720/big_buck_bunny_720p_5mb.mp4") #> download !

    "qt-faststart download sample.mp4".!
    download.delete()
  }


  // Request a clip starting at 4 seconds, through to 12 second mark.
  val request = ffmpeg ? RequestClip(new FileInputStream(sample), start = 4, end = 12)

  // Write the resulting InputStream to a file.
  // It should be about ~526,890 bytes, NOT ~1243!!!
  val result = request flatMap {
    case Clip(output) =>
      output.runWith(FileIO.toFile(new File("clip.mp4")))
  } foreach { _ =>
    system.terminate()
  }
}
