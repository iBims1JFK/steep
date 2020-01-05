package helper

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

/**
 * Provides methods to execute shell commands
 * @author Michel Kraemer
 */
object Shell {
  private val log = LoggerFactory.getLogger(Shell::class.java)

  /**
   * Executes the given command
   * @param command the command
   * @param outputLinesToCollect the number of output lines to collect at most
   * @return the command's output (stdout and stderr)
   * @throws ExecutionException if the command failed
   */
  fun execute(command: List<String>, outputLinesToCollect: Int = 100): String {
    return execute(command, File(System.getProperty("user.dir")), outputLinesToCollect)
  }

  /**
   * Executes the given command in the given working directory
   * @param command the command
   * @param workingDir the working directory
   * @param outputLinesToCollect the number of output lines to collect at most
   * @return the command's output (stdout and stderr)
   * @throws ExecutionException if the command failed
   */
  private fun execute(command: List<String>, workingDir: File,
      outputLinesToCollect: Int = 100): String {
    val joinedCommand = command.joinToString(" ")
    log.info(joinedCommand)

    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()

    val streamGobbler = StreamGobbler(process.inputStream, outputLinesToCollect)
    val readerThread = Thread(streamGobbler)

    try {
      process.waitFor()
    } catch (e: InterruptedException) {
      process.destroy()
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        log.warn("Unable to destroy process after 10s. Trying to destroy forcibly ...")
        process.destroyForcibly()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          log.error("Unable to forcibly destroy process after 20s.")
          throw InterruptedException("Unable to forcibly destroy process")
        }
      }
      throw e
    } finally {
      readerThread.join()
    }

    val code = process.exitValue()
    val result = streamGobbler.lines().joinToString("\n")
    if (code != 0) {
      log.error("Command failed with exit code: $code")
      throw ExecutionException("Failed to run `$joinedCommand'", result, code)
    }

    return result
  }

  /**
   * An exception thrown by [execute] if the command failed
   * @param message a generic error message
   * @param lastOutput the last output collected from the command before it
   * failed (may contain the actual error message issued by the command)
   * @param exitCode the command's exit code
   */
  class ExecutionException(
      message: String,
      val lastOutput: String,
      val exitCode: Int
  ) : IOException(message)

  /**
   * A background thread that reads all data from an [inputStream] and collects
   * as many lines as [outputLinesToCollect]
   */
  private class StreamGobbler(private val inputStream: InputStream,
      private val outputLinesToCollect: Int) : Runnable {
    private val lines = LinkedList<String>()

    override fun run() {
      inputStream.bufferedReader().forEachLine { line ->
        log.info(line)
        synchronized(this) {
          lines.add(line)
          if (lines.size > outputLinesToCollect) {
            lines.removeFirst()
          }
        }
      }
    }

    /**
     * Return the collected lines
     */
    fun lines(): List<String> {
      synchronized(this) {
        return lines
      }
    }
  }
}
