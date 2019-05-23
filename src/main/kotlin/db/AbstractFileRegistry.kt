package db

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import helper.FileSystemUtils
import helper.JsonUtils
import helper.YamlUtils
import io.vertx.core.Vertx
import io.vertx.kotlin.core.file.readFileAwait
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Abstract base class for registries that read information from JSON or YAML
 * files using globs.
 * @author Michel Kraemer
 */
abstract class AbstractFileRegistry {
  /**
   * Reads the information from the JSON or YAML files
   * @param paths the paths/globs to the JSON or YAML files
   * @param vertx the Vert.x instance
   * @return the information
   */
  protected suspend inline fun <reified I, reified T : List<I>> find(
      paths: List<String>, vertx: Vertx): List<I> {
    val matchers = paths.map { path ->
      FileSystems.getDefault().getPathMatcher("glob:$path")
    }
    val cwd = Path.of("").toAbsolutePath().toString().let {
      if (it.endsWith("/")) it else "$it/"
    }

    // We need this here to get access to T.
    // com.fasterxml.jackson.module.kotlin.readValue does not work inside
    // the flatMap
    val tr = jacksonTypeRef<T>()

    val files = FileSystemUtils.readRecursive(cwd, vertx.fileSystem()) { file ->
      val p = Path.of(file.substring(cwd.length))
      matchers.any { it.matches(p) }
    }

    return files.flatMap { file ->
      val content = vertx.fileSystem().readFileAwait(file).toString()
      if (file.toLowerCase().endsWith(".json")) {
        JsonUtils.mapper.readValue<T>(content, tr)
      } else {
        YamlUtils.mapper.readValue<T>(content, tr)
      }
    }
  }
}