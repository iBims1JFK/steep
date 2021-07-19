import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_MISSING
import AddressConstants.SCHEDULER_LOOKUP_NOW
import AddressConstants.SCHEDULER_LOOKUP_ORPHANS_NOW
import AddressConstants.SCHEDULER_PREFIX
import AddressConstants.SCHEDULER_RUNNING_PROCESS_CHAINS_SUFFIX
import ConfigConstants.SCHEDULER_LOOKUP_INTERVAL
import ConfigConstants.SCHEDULER_LOOKUP_ORPHANS_INITIAL_DELAY
import ConfigConstants.SCHEDULER_LOOKUP_ORPHANS_INTERVAL
import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus.CANCELLED
import db.SubmissionRegistry.ProcessChainStatus.ERROR
import db.SubmissionRegistry.ProcessChainStatus.REGISTERED
import db.SubmissionRegistry.ProcessChainStatus.RUNNING
import db.SubmissionRegistry.ProcessChainStatus.SUCCESS
import db.SubmissionRegistryFactory
import io.prometheus.client.Gauge
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * The scheduler fetches process chains from a [SubmissionRegistry], executes
 * them through [Agent]s, and puts the results back into the
 * [SubmissionRegistry].
 * @author Michel Kraemer
 */
class Scheduler : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /**
     * The number of process chains with a given status
     */
    private val gaugeProcessChains = Gauge.build()
        .name("steep_scheduler_process_chains")
        .labelNames("status")
        .help("Number of process chains with a given status")
        .register()

    /**
     * Name of a cluster-wide map keeping IDs of [Scheduler] instances
     */
    private const val ASYNC_MAP_NAME = "Scheduler.Async"
  }

  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentRegistry: AgentRegistry

  private lateinit var periodicLookupJob: Job
  private var periodicLookupOrphansJob: Job? = null

  /**
   * A list of sets of capabilities required by process chains with the status
   * [REGISTERED] and the respective number of process chains
   */
  private var allRequiredCapabilities: MutableList<Pair<Collection<String>, Long>> = mutableListOf()
  private var allRequiredCapabilitiesInitialized = false

  /**
   * The remaining number of lookups to do in [lookup]
   */
  private var pendingLookups = 0L

  /**
   * IDs of all process chains we are currently executing
   */
  private val runningProcessChainIds = mutableSetOf<String>()

  /**
   * A cluster-wide map keeping IDs of [Scheduler] instances
   */
  private lateinit var schedulers: AsyncMap<String, Boolean>

  /**
   * Main agent ID of this Steep instance
   */
  private lateinit var agentId: String

  /**
   * A list of pairs of process chain IDs and agent info objects specifying
   * which process chain should be resumed on which agent
   */
  private val processChainsToResume = mutableListOf<Pair<String, JsonObject>>()

  override suspend fun start() {
    log.info("Launching scheduler ...")

    agentId = config.getString(ConfigConstants.AGENT_ID) ?:
        throw IllegalStateException("Missing configuration item " +
            "`${ConfigConstants.AGENT_ID}'")

    // register scheduler in cluster-wide map
    registerScheduler()

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)

    // read configuration
    val lookupInterval = config.getLong(SCHEDULER_LOOKUP_INTERVAL, 20000L)
    val lookupOrphansInterval = config.getLong(SCHEDULER_LOOKUP_ORPHANS_INTERVAL, 300_000L)
    val lookupOrphansInitialDelay = config.getLong(SCHEDULER_LOOKUP_ORPHANS_INITIAL_DELAY, 0L)

    // periodically look for new process chains and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        try {
          lookup(updateRequiredCapabilities = true)
        } catch (t: Throwable) {
          log.error("Failed to look for process chains", t)
        }
      }
    }

    vertx.eventBus().consumer<JsonObject?>(SCHEDULER_LOOKUP_NOW) { msg ->
      val maxLookups = msg.body()?.getInteger("maxLookups") ?: Int.MAX_VALUE
      val updateRequiredCapabilities = msg.body()?.getBoolean("updateRequiredCapabilities") ?: true
      launch {
        lookup(maxLookups, updateRequiredCapabilities)
      }
    }

    vertx.eventBus().consumer<Unit>(SCHEDULER_LOOKUP_ORPHANS_NOW) {
      launch {
        lookupOrphans()
      }
    }

    val addressRunningProcessChains =
        "$SCHEDULER_PREFIX$agentId$SCHEDULER_RUNNING_PROCESS_CHAINS_SUFFIX"
    vertx.eventBus().consumer<Any?>(addressRunningProcessChains) { msg ->
      msg.reply(JsonArray(runningProcessChainIds.toList()))
    }

    if (lookupOrphansInitialDelay > 0) {
      vertx.setTimer(lookupOrphansInitialDelay) {
        startLookupOrphansJob(lookupOrphansInterval)
      }
    } else {
      startLookupOrphansJob(lookupOrphansInterval)
    }
  }

  /**
   * Register scheduler in cluster-wide map and initialize consumer that
   * unregisters other scheduler instances if their nodes have left
   */
  private suspend fun registerScheduler() {
    val sharedData = vertx.sharedData()
    val schedulersPromise = Promise.promise<AsyncMap<String, Boolean>>()
    sharedData.getAsyncMap(ASYNC_MAP_NAME, schedulersPromise)
    schedulers = schedulersPromise.future().await()

    // unregister schedulers whose nodes have left
    vertx.eventBus().localConsumer<JsonObject>(CLUSTER_NODE_LEFT) { msg ->
      launch {
        val theirAgentId = msg.body().getString("agentId")
        log.trace("Node `$theirAgentId' has left the cluster. Removing scheduler.")
        schedulers.removeAwait(theirAgentId)

        // look for orphaned process chains the scheduler might have left behind
        lookupOrphans()
      }
    }

    // register our own instance in the map
    schedulers.putAwait(agentId, true)
  }

  /**
   * Start a periodic job that looks for orphaned process chains and execute
   * it right away.
   */
  private fun startLookupOrphansJob(lookupOrphansInterval: Long) {
    // periodically look for orphaned running process chains and re-execute them
    periodicLookupOrphansJob = launch {
      while (true) {
        delay(lookupOrphansInterval)
        lookupOrphans()
      }
    }
    launch {
      // look up for orphaned running process chains now
      lookupOrphans()
    }
  }

  override suspend fun stop() {
    log.info("Stopping scheduler ...")
    periodicLookupJob.cancelAndJoin()
    periodicLookupOrphansJob?.cancelAndJoin()
    submissionRegistry.close()
    schedulers.removeAwait(agentId)
  }

  /**
   * Get registered process chains and execute them asynchronously
   * @param maxLookups the maximum number of lookups to perform
   * @param updateRequiredCapabilities `true` if the list of known required
   * capabilities should be updated before performing the lookup
   */
  private suspend fun lookup(maxLookups: Int = Int.MAX_VALUE,
      updateRequiredCapabilities: Boolean) {
    // increase number of pending lookups and then check if we actually need
    // to proceed here
    val oldPendingLookups = pendingLookups
    pendingLookups = (pendingLookups + maxLookups).coerceAtMost(Int.MAX_VALUE.toLong())
    if (oldPendingLookups > 0L) {
      // Nothing to do here. There is another lookup call running.
      return
    }

    if (updateRequiredCapabilities || !allRequiredCapabilitiesInitialized) {
      val arcs = submissionRegistry.findProcessChainRequiredCapabilities(REGISTERED)

      // count process chains for each required capability set
      allRequiredCapabilities = arcs.map { rc ->
        rc to submissionRegistry.countProcessChains(status = REGISTERED,
            requiredCapabilities = rc)
      }.toMutableList()
      allRequiredCapabilitiesInitialized = true
    }

    while (pendingLookups > 0L) {
      val start = System.currentTimeMillis()

      val allocatedProcessChains = lookupStep()

      if (allocatedProcessChains == 0) {
        // all agents are busy
        pendingLookups = 0
        break
      } else {
        log.debug("Scheduling $allocatedProcessChains process " +
            "chain${if (allocatedProcessChains > 1) "s" else ""} " +
            "took ${System.currentTimeMillis() - start} ms")
      }

      pendingLookups--
    }
  }

  /**
   * One step in the scheduling process controlled by [lookup]. Returns the
   * number of process chains successfully allocated to an agent.
   */
  private suspend fun lookupStep(): Int {
    // send all known required capabilities to all agents and ask them if they
    // are available and, if so, what required capabilities they can handle
    val candidates = selectCandidates()
    if (candidates.isEmpty()) {
      // Agents are all busy or do not accept our required capabilities.
      // Check if we need to request a new agent.
      val rcsi = allRequiredCapabilities.iterator()
      while (rcsi.hasNext()) {
        val rcs = rcsi.next()
        if (!submissionRegistry.existsProcessChain(REGISTERED, rcs.first)) {
          // if there is no such process chain, the capabilities are not
          // required anymore
          rcsi.remove()
        } else {
          // publish a message that says we need an agent with the given
          // capabilities
          val msg = json {
            obj(
                "n" to rcs.second,
                "requiredCapabilities" to JsonArray(rcs.first.toList())
            )
          }
          vertx.eventBus().publish(REMOTE_AGENT_MISSING, msg)
        }
      }
      return 0
    }

    // iterate through all agents that indicated they are available
    var allocatedProcessChains = 0
    for ((requiredCapabilities, address) in candidates) {
      val arci = allRequiredCapabilities.indexOfFirst { it.first == requiredCapabilities }

      // get next registered process chain for the given set of required capabilities
      val (processChain, isProcessChainResumed) = fetchNextProcessChain(address, requiredCapabilities)
      if (processChain == null) {
        // We didn't find a process chain for these required capabilities.
        // Remove them from the list of known ones.
        if (arci >= 0) {
          allRequiredCapabilities.removeAt(arci)
        }
        continue
      }

      if (requiredCapabilities.isEmpty()) {
        log.info("Found registered process chain `${processChain.id}'")
      } else {
        log.info("Found registered process chain `${processChain.id}' for " +
            "required capabilities `$requiredCapabilities'")
      }

      // allocate an agent for the process chain
      val agent = agentRegistry.tryAllocate(address, processChain.id)
      if (agent == null) {
        log.warn("Agent with address `$address' did not accept process " +
            "chain `${processChain.id}'")
        submissionRegistry.setProcessChainStatus(processChain.id, REGISTERED)

        // continue with the next capability set and candidate
        continue
      }

      log.info("Assigned process chain `${processChain.id}' to agent `${agent.id}'")
      allocatedProcessChains++
      runningProcessChainIds.add(processChain.id)

      // update number of remaining process chains for this required capability set
      if (arci >= 0) {
        val rc = allRequiredCapabilities[arci]
        allRequiredCapabilities[arci] = rc.copy(second = (rc.second - 1).coerceAtLeast(0))
      }

      // execute process chain
      launch {
        try {
          gaugeProcessChains.labels(RUNNING.name).inc()
          if (!isProcessChainResumed) {
            submissionRegistry.setProcessChainStartTime(processChain.id, Instant.now())
          }

          val results = agent.execute(processChain)

          submissionRegistry.setProcessChainResults(processChain.id, results)
          submissionRegistry.setProcessChainStatus(processChain.id, SUCCESS)
          gaugeProcessChains.labels(SUCCESS.name).inc()
        } catch (_: CancellationException) {
          log.warn("Process chain execution was cancelled")
          submissionRegistry.setProcessChainStatus(processChain.id, CANCELLED)
          gaugeProcessChains.labels(CANCELLED.name).inc()
        } catch (t: Throwable) {
          log.error("Process chain execution failed", t)
          submissionRegistry.setProcessChainErrorMessage(processChain.id, t.message)
          submissionRegistry.setProcessChainStatus(processChain.id, ERROR)
          gaugeProcessChains.labels(ERROR.name).inc()
        } finally {
          gaugeProcessChains.labels(RUNNING.name).dec()
          agentRegistry.deallocate(agent)
          submissionRegistry.setProcessChainEndTime(processChain.id, Instant.now())
          runningProcessChainIds.remove(processChain.id)

          // try to lookup next process chain immediately
          vertx.eventBus().send(SCHEDULER_LOOKUP_NOW, json {
            obj(
                "maxLookups" to 1,
                "updateRequiredCapabilities" to false
            )
          })
        }
      }
    }

    return allocatedProcessChains
  }

  /**
   * Select candidate agents by either returning entries from
   * [processChainsToResume] or by forwarding the request to
   * [AgentRegistry.selectCandidates].
   */
  private suspend fun selectCandidates(): List<Pair<Collection<String>, String>> {
    if (processChainsToResume.isNotEmpty()) {
      return processChainsToResume.map { entry ->
        val agentInfo = entry.second
        val id = agentInfo.getString("id")
        val address = REMOTE_AGENT_ADDRESS_PREFIX + id
        val capabilities = agentInfo.getJsonArray("capabilities")
            ?.list?.map { it.toString() } ?: emptyList()
        capabilities to address
      }
    }

    return agentRegistry.selectCandidates(allRequiredCapabilities)
  }

  /**
   * Fetch next process chain to schedule either from [processChainsToResume]
   * or from the [submissionRegistry]. Return a pair with the process chain
   * and a flag specifying if the process chain is resumed or not.
   */
  private suspend fun fetchNextProcessChain(agentAddress: String,
      requiredCapabilities: Collection<String>): Pair<ProcessChain?, Boolean> {
    if (processChainsToResume.isNotEmpty()) {
      val pci = processChainsToResume.indexOfFirst { pair ->
        val agentId = pair.second.getString("id")
        val addr = REMOTE_AGENT_ADDRESS_PREFIX + agentId
        addr == agentAddress
      }
      if (pci >= 0) {
        val processChainId = processChainsToResume[pci].first
        processChainsToResume.removeAt(pci)
        return submissionRegistry.findProcessChainById(processChainId) to true
      }
    }

    return submissionRegistry.fetchNextProcessChain(
        REGISTERED, RUNNING, requiredCapabilities) to false
  }

  /**
   * Check for orphaned running submissions and resume their execution
   */
  private suspend fun lookupOrphans() {
    if (processChainsToResume.isNotEmpty()) {
      // There are still process chains to resume. Stop here. Otherwise, the
      // same process chains will be added again
      return
    }

    try {
      // get all process chains with status RUNNING from the registry
      // IMPORTANT: we need to do this first before we ask the schedulers which
      // process chains they are executing. Otherwise, we might risk finding
      // chains that have been started by a scheduler right after we asked it.
      val runningProcessChains = submissionRegistry.findProcessChainIdsByStatus(
          status = RUNNING)

      // ask all scheduler instances which process chains they are currently executing
      val allRunningProcessChains = mutableSetOf<String>()
      val keysPromise = Promise.promise<Set<String>>()
      schedulers.keys(keysPromise)
      for (scheduler in keysPromise.future().await()) {
        val address = "$SCHEDULER_PREFIX$scheduler$SCHEDULER_RUNNING_PROCESS_CHAINS_SUFFIX"
        val ids = vertx.eventBus().requestAwait<JsonArray>(address, null)
        for (id in ids.body()) {
          allRunningProcessChains.add(id.toString())
        }
      }

      // find those process chains that are not executed by any scheduler
      val orphanedCandidates = runningProcessChains.filterNot {
        allRunningProcessChains.contains(it) }
      if (orphanedCandidates.isEmpty()) {
        // nothing to do
        return
      }

      // check again if orphaned process chains are still running (or if they
      // had just been finished by a scheduler before we had the chance to ask it)
      val stillRunningProcessChains = submissionRegistry.findProcessChainIdsByStatus(
          status = RUNNING).toSet()
      val orphanedProcessChains = orphanedCandidates.filter {
        stillRunningProcessChains.contains(it) }
      if (orphanedProcessChains.isEmpty()) {
        // nothing to do
        return
      }

      log.info("Found ${orphanedProcessChains.size} orphaned running process " +
          "chains. Trying to resume ...")

      // ask all agents which process chains they are currently executing
      val agentIds = agentRegistry.getAgentIds()
      val msg = json {
        obj(
            "action" to "info"
        )
      }
      val agentInfos = agentIds.map { vertx.eventBus().requestAwait<JsonObject>(
          REMOTE_AGENT_ADDRESS_PREFIX + it, msg) }.map { it.body() }
      val processChainsToAgents = agentInfos.mapNotNull { info ->
        val pcId = info.getString("processChainId")
        if (pcId != null) {
          pcId to info
        } else {
          null
        }
      }.toMap()

      // differentiate between process chains that are actually still being
      // executed by an agent and those that are not executed anymore at all
      val orphansWithAgents = mutableListOf<Pair<String, JsonObject>>()
      val orphansWithoutAgents = mutableSetOf<String>()
      for (id in orphanedProcessChains) {
        val agentInfo = processChainsToAgents[id]
        if (agentInfo != null) {
          orphansWithAgents.add(id to agentInfo)
        } else {
          orphansWithoutAgents.add(id)
        }
      }

      // reset state of orphaned process chains that are actually not being
      // executed by an agent
      for (id in orphansWithoutAgents) {
        submissionRegistry.setProcessChainStatus(id, REGISTERED)
        submissionRegistry.setProcessChainStartTime(id, null)
      }

      // resume process chains with agents in `lookup` method
      processChainsToResume.addAll(orphansWithAgents)
      launch {
        lookup(updateRequiredCapabilities = false)
      }
    } catch (t: Throwable) {
      // Just log errors but don't treat them any further. Just wait until the
      // next lookup and then try again. We should only resume process chains
      // if everything runs through without any problems.
      log.error("Failed to resume orphaned process chains", t)
    }
  }
}
