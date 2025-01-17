/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * The path to the files containing service metadata. Either a string
   * pointing to a single file or a glob (e.g. &#42;&#42;&#47;&#42;.yaml) or
   * an array of files or globs.
   */
  const val SERVICES = "steep.services"

  /**
   * The path to the file(s) containing plugin descriptors. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val PLUGINS = "steep.plugins"

  /**
   * A file that keeps additional configuration (overrides the main
   * configuration). Note that configuration items in this file can still be
   * overridden with environment variables.
   */
  const val OVERRIDE_CONFIG_FILE = "steep.overrideConfigFile"

  /**
   * Path where temporary files should be stored
   */
  const val TMP_PATH = "steep.tmpPath"

  /**
   * Path where output files should be stored
   */
  const val OUT_PATH = "steep.outPath"

  /**
   * The IP address (or hostname) to bind the clustered eventbus to
   */
  const val CLUSTER_EVENTBUS_HOST = "steep.cluster.eventBus.host"

  /**
   * The port the clustered eventbus should listen on
   */
  const val CLUSTER_EVENTBUS_PORT = "steep.cluster.eventBus.port"

  /**
   * The IP address (or hostname) the eventbus uses to announce itself within
   * in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_HOST = "steep.cluster.eventBus.publicHost"

  /**
   * The port that the eventbus uses to announce itself within in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_PORT = "steep.cluster.eventBus.publicPort"

  /**
   * The IP address (or hostname) and port Hazelcast uses to announce itself
   * within in the cluster
   */
  const val CLUSTER_HAZELCAST_PUBLIC_ADDRESS = "steep.cluster.hazelcast.publicAddress"

  /**
   * The port that Hazelcast should listen on
   */
  const val CLUSTER_HAZELCAST_PORT = "steep.cluster.hazelcast.port"

  /**
   * A list of IP address patterns specifying valid interfaces Hazelcast
   * should bind to
   */
  const val CLUSTER_HAZELCAST_INTERFACES = "steep.cluster.hazelcast.interfaces"

  /**
   * A list of IP addresses (or hostnames) of Hazelcast cluster members
   */
  const val CLUSTER_HAZELCAST_MEMBERS = "steep.cluster.hazelcast.members"

  /**
   * `true` if Hazelcast should use TCP to connect to other instances, `false`
   * if it should use multicast
   */
  const val CLUSTER_HAZELCAST_TCPENABLED = "steep.cluster.hazelcast.tcpEnabled"

  /**
   * `true` if the IP addresses from potential Hazelcast cluster members should
   * be restored on startup from the [db.VMRegistry] (i.e. VMs that are still
   * running will automatically be added to the list of members)
   */
  const val CLUSTER_HAZELCAST_RESTORE_MEMBERS_ENABLED =
      "steep.cluster.hazelcast.restoreMembersOnStartup.enabled"

  /**
   * If [CLUSTER_HAZELCAST_RESTORE_MEMBERS_ENABLED] is `true`, potential
   * Hazelcast members will be restored from the [db.VMRegistry]. This
   * configuration item specifies on which Hazelcast port these members are
   * listening.
   */
  const val CLUSTER_HAZELCAST_RESTORE_MEMBERS_DEFAULT_PORT =
      "steep.cluster.hazelcast.restoreMembersOnStartup.defaultPort"

  /**
   * `true` if an HTTP server should be deployed
   */
  const val HTTP_ENABLED = "steep.http.enabled"

  /**
   * The host to bind the HTTP server to
   */
  const val HTTP_HOST = "steep.http.host"

  /**
   * The port the HTTP server should listen on
   */
  const val HTTP_PORT = "steep.http.port"

  /**
   * The maximum size of HTTP POST bodies in bytes
   */
  const val HTTP_POST_MAX_SIZE = "steep.http.postMaxSize"

  /**
   * The path where the HTTP endpoints should be mounted
   */
  const val HTTP_BASE_PATH = "steep.http.basePath"

  /**
   * `true` if Cross-Origin Resource Sharing (CORS) should be enabled
   */
  const val HTTP_CORS_ENABLE = "steep.http.cors.enable"

  /**
   * A regular expression specifying allowed CORS origins. Use *​ to allow all origins.
   */
  const val HTTP_CORS_ALLOW_ORIGIN = "steep.http.cors.allowOrigin"

  /**
   * `true` if the Access-​Control-​Allow-​Credentials` response header should
   * be returned.
   */
  const val HTTP_CORS_ALLOW_CREDENTIALS = "steep.http.cors.allowCredentials"

  /**
   * A string or an array indicating which header field names can be used
   * in a request.
   */
  const val HTTP_CORS_ALLOW_HEADERS = "steep.http.cors.allowHeaders"

  /**
   * A string or an array indicating which HTTP methods can be used in a
   * request.
   */
  const val HTTP_CORS_ALLOW_METHODS = "steep.http.cors.allowMethods"

  /**
   * A string or an array indicating which headers are safe to expose to the
   * API of a CORS API specification.
   */
  const val HTTP_CORS_EXPOSE_HEADERS = "steep.http.cors.exposeHeaders"

  /**
   * The number of seconds the results of a preflight request can be cached in
   * a preflight result cache.
   */
  const val HTTP_CORS_MAX_AGE = "steep.http.cors.maxAge"

  /**
   * `true` if the controller should be enabled. Set this value to `false` if
   * your Steep instance does not have access to the shared database.
   */
  const val CONTROLLER_ENABLED = "steep.controller.enabled"

  /**
   * The interval at which the controller looks for accepted submissions
   */
  const val CONTROLLER_LOOKUP_INTERVAL = "steep.controller.lookupIntervalMilliseconds"

  /**
   * The interval at which the controller looks for orphaned running
   * submissions (i.e. submissions that are in the status `RUNNING' but that
   * are currently not being processed by any [Controller])
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INTERVAL = "steep.controller.lookupOrphansIntervalMilliseconds"

  /**
   * The number of milliseconds the controller should wait after startup before
   * it looks for orphaned running submissions for the first time. This property
   * is useful if you want to implement a rolling update from one Steep
   * instance to another.
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INITIAL_DELAY = "steep.controller.lookupOrphansInitialDelayMilliseconds"

  /**
   * `true` if the scheduler should be enabled. Set this value to `false` if
   * your Steep instance does not have access to the shared database.
   */
  const val SCHEDULER_ENABLED = "steep.scheduler.enabled"

  /**
   * The interval in which the scheduler looks for registered process chains
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "steep.scheduler.lookupIntervalMilliseconds"

  /**
   * The interval at which the scheduler looks for orphaned running
   * process chains (i.e. process chains that are in the status `RUNNING' but
   * that are currently not being processed by any [Scheduler]). Note that
   * the scheduler also always looks for orphaned process chains when it detects
   * that another scheduler instance has just left the cluster (regardless of
   * the configured interval).
   */
  const val SCHEDULER_LOOKUP_ORPHANS_INTERVAL = "steep.scheduler.lookupOrphansIntervalMilliseconds"

  /**
   * The number of milliseconds the scheduler should wait after startup before
   * it looks for orphaned running process chains for the first time. This
   * property is useful if you want to implement a rolling update from one Steep
   * instance to another. Note that the scheduler also looks for orphaned
   * process chains when another scheduler instance has just left the cluster,
   * even if the initial delay has not passed by yet.
   */
  const val SCHEDULER_LOOKUP_ORPHANS_INITIAL_DELAY = "steep.scheduler.lookupOrphansInitialDelayMilliseconds"

  /**
   * `true` if this Steep instance should be able to execute process
   * chains through [agent.LocalAgent]
   */
  const val AGENT_ENABLED = "steep.agent.enabled"

  /**
   * Unique identifier of this agent instance
   */
  const val AGENT_ID = "steep.agent.id"

  /**
   * List of capabilities that this agent provides
   */
  const val AGENT_CAPABILTIIES = "steep.agent.capabilities"

  /**
   * The number of instances to create of this agent (i.e. how many times it
   * should be deployed in the same JVM). Defaults to `1`.
   */
  const val AGENT_INSTANCES = "steep.agent.instances"

  /**
   * The number of minutes an agent should remain idle until it shuts itself
   * down gracefully. By default, this value is `0`, which means the agent
   * never shuts itself down.
   */
  const val AGENT_AUTO_SHUTDOWN_TIMEOUT = "steep.agent.autoShutdownTimeoutMinutes"

  /**
   * The number of seconds that should pass before an idle agent decides
   * that it is not busy anymore
   */
  const val AGENT_BUSY_TIMEOUT = "steep.agent.busyTimeoutSeconds"

  /**
   * The number of output lines to collect at most from each executed service
   * (also applies to error output)
   */
  const val AGENT_OUTPUT_LINES_TO_COLLECT = "steep.agent.outputLinesToCollect"

  /**
   * Additional environment variables to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_ENV = "steep.runtimes.docker.env"

  /**
   * Additional volume mounts to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_VOLUMES= "steep.runtimes.docker.volumes"

  /**
   * The database driver (see [db.SubmissionRegistryFactory] for valid values)
   */
  const val DB_DRIVER = "steep.db.driver"

  /**
   * The database URL
   */
  const val DB_URL = "steep.db.url"

  /**
   * The database username
   */
  const val DB_USERNAME = "steep.db.username"

  /**
   * The database password
   */
  const val DB_PASSWORD = "steep.db.password"

  /**
   * `true` if Steep should connect to a cloud to acquire remote
   * agents on demand
   */
  const val CLOUD_ENABLED = "steep.cloud.enabled"

  /**
   * Defines which [cloud.CloudClient] to use
   */
  const val CLOUD_DRIVER = "steep.cloud.driver"

  /**
   * A tag that should be attached to virtual machines to indicate that they
   * have been created by Steep
   */
  const val CLOUD_CREATED_BY_TAG = "steep.cloud.createdByTag"

  /**
   * The path to the file that describes all available setups
   */
  const val CLOUD_SETUPS_FILE = "steep.cloud.setupsFile"

  /**
   * The number of seconds that should pass before the Cloud manager syncs
   * its internal state with the Cloud again
   */
  const val CLOUD_SYNC_INTERVAL = "steep.cloud.syncIntervalSeconds"

  /**
   * The number of seconds that should pass before the Cloud manager sends
   * keep-alive messages to a minimum of remote agents again (so that they
   * do not shut down themselves). See [model.setup.Setup.minVMs].
   */
  const val CLOUD_KEEP_ALIVE_INTERVAL = "steep.cloud.keepAliveIntervalSeconds"

  /**
   * Describes parameters of remote agents the CloudManager maintains in its pool
   */
  const val CLOUD_AGENTPOOL = "steep.cloud.agentPool"

  /**
   * OpenStack authentication endpoint
   */
  const val CLOUD_OPENSTACK_ENDPOINT = "steep.cloud.openstack.endpoint"

  /**
   * OpenStack username used for authentication
   */
  const val CLOUD_OPENSTACK_USERNAME = "steep.cloud.openstack.username"

  /**
   * OpenStack password used for authentication
   */
  const val CLOUD_OPENSTACK_PASSWORD = "steep.cloud.openstack.password"

  /**
   * OpenStack domain name used for authentication
   */
  const val CLOUD_OPENSTACK_DOMAIN_NAME = "steep.cloud.openstack.domainName"

  /**
   * The ID of the OpenStack project to connect to.Either
   * [CLOUD_OPENSTACK_PROJECT_ID] or [CLOUD_OPENSTACK_PROJECT_NAME] must be set
   * but not both at the same time.
   */
  const val CLOUD_OPENSTACK_PROJECT_ID = "steep.cloud.openstack.projectId"

  /**
   * The name of the OpenStack project to connect to. Will be used in
   * combination with [CLOUD_OPENSTACK_DOMAIN_NAME] if
   * [CLOUD_OPENSTACK_PROJECT_ID] is not set.
   */
  const val CLOUD_OPENSTACK_PROJECT_NAME = "steep.cloud.openstack.projectName"

  /**
   * The ID of the OpenStack network to attach new VMs to
   */
  const val CLOUD_OPENSTACK_NETWORK_ID = "steep.cloud.openstack.networkId"

  /**
   * `true` if new VMs should have a public IP address
   */
  const val CLOUD_OPENSTACK_USE_PUBLIC_IP = "steep.cloud.openstack.usePublicIp"

  /**
   * The OpenStack security groups that new VMs should be put in
   */
  const val CLOUD_OPENSTACK_SECURITY_GROUPS = "steep.cloud.openstack.securityGroups"

  /**
   * The OpenStack keypair to deploy to new VMs
   */
  const val CLOUD_OPENSTACK_KEYPAIR_NAME = "steep.cloud.openstack.keypairName"

  /**
   * Username for SSH access to VMs
   */
  const val CLOUD_SSH_USERNAME = "steep.cloud.ssh.username"

  /**
   * Location of a private key to use for SSH
   */
  const val CLOUD_SSH_PRIVATE_KEY_LOCATION = "steep.cloud.ssh.privateKeyLocation"

  /**
   * The default log level for all loggers (one of `TRACE`, `DEBUG`, `INFO`,
   * `WARN`, `ERROR`, `OFF`). The default value is `DEBUG`.
   */
  const val LOGS_LEVEL = "steep.logs.level"

  /**
   * `true` if logging to the main log file should be enabled. The default
   * value is `false`.
   */
  const val LOGS_MAIN_ENABLED = "steep.logs.main.enabled"

  /**
   * The name of the main log file. The default is `logs/steep.log`.
   */
  const val LOGS_MAIN_LOGFILE = "steep.logs.main.logFile"

  /**
   * `true` if log files should be renamed every day. The file name of old
   * logs will be based on the main log file name [LOGS_MAIN_LOGFILE] and the
   * file's date in the form `YYYY-MM-DD` (e.g. `steep.2020-11-19.log`). The
   * default value is `true`.
   */
  const val LOGS_MAIN_DAILYROLLOVER_ENABLED = "steep.logs.main.dailyRollover.enabled"

  /**
   * The maximum number of days' worth of log files to keep. The default
   * value is `7`.
   */
  const val LOGS_MAIN_DAILYROLLOVER_MAXDAYS = "steep.logs.main.dailyRollover.maxDays"

  /**
   * The total maximum size of all log files. Oldest log files will deleted
   * when this size is reached. The default value is `104857600` (= 100 MB)
   */
  const val LOGS_MAIN_DAILYROLLOVER_MAXSIZE = "steep.logs.main.dailyRollover.maxSize"

  /**
   * `true` if the output of process chains should be logged separately to disk.
   * The output will still also appear on the console and in the main log file
   * (if enabled), but there, it's not separated by process chain. This feature
   * is useful if you want to record the output of individual process chains
   * and make it available through the `/logs/processchains` HTTP endpoint.
   * The default value is `false`.
   */
  const val LOGS_PROCESSCHAINS_ENABLED = "steep.logs.processChains.enabled"

  /**
   * The path where process chain logs will be stored. Individual files will
   * will be named after the ID of the corresponding process chain (e.g.
   * `aprsqz6d5f4aiwsdzbsq.log`). The default value is `logs/processchains`.
   */
  const val LOGS_PROCESSCHAINS_PATH = "steep.logs.processChains.path"

  /**
   * Set this configuration item to a value greater than `0` to group process
   * chain log files by prefix in subdirectories under the directory configured
   * through [LOGS_PROCESSCHAINS_PATH]. For example, if this configuration
   * item is set to `3`, Steep will create a separate subdirectory for all
   * process chains whose ID starts with the same three characters. The name of
   * this subdirectory will be these three characters. The process chains
   * `apomaokjbk3dmqovemwa` and `apomaokjbk3dmqovemsq` will be put into a
   * subdirectory called `apo`, and the process chain `ao344a53oyoqwhdelmna`
   * will be put into `ao3`. Note that in practice, `3` is a reasonable value,
   * which will create a new directory about every day. A value of `0` disables
   * grouping. The default value is `0`.
   */
  const val LOGS_PROCESSCHAINS_GROUPBYPREFIX = "steep.logs.processChains.groupByPrefix"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
