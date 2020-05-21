package cloud

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import ConfigConstants
import agent.AgentRegistry
import agent.AgentRegistryFactory
import coVerify
import db.VMRegistry
import db.VMRegistryFactory
import helper.LazyJsonObjectMessageCodec
import helper.UniqueID
import helper.YamlUtils
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.cloud.VM
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.IllegalArgumentException
import java.nio.file.Path

/**
 * Tests for [CloudManager]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class CloudManagerTest {
  companion object {
    private const val MY_OLD_VM = "MY_OLD_VM"
    private const val CREATED_BY_TAG = "CloudManagerTest"
    private const val SYNC_INTERVAL = 2
    private const val KEEP_ALIVE_INTERVAL = 1
    private const val AZ01 = "az-01"
    private const val AZ02 = "az-02"
  }

  private lateinit var client: CloudClient
  private lateinit var cloudManager: CloudManager

  @BeforeEach
  fun setUp(vertx: Vertx) {
    // mock cloud client
    client = mockk()
    mockkObject(CloudClientFactory)
    every { CloudClientFactory.create(any()) } returns client

    // mock SSH client
    mockkConstructor(SSHClient::class)
    coEvery { anyConstructed<SSHClient>().tryConnect(any()) } just Runs

    // necessary for the NotifyingVMRegistry
    vertx.eventBus().registerCodec(LazyJsonObjectMessageCodec())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Deploy the [CloudManager] verticle and complete the given test context
   */
  private fun deployCloudManager(tempDir: Path, setups: List<Setup>,
      vertx: Vertx, ctx: VertxTestContext, agentPool: JsonArray = JsonArray()) {
    deployCloudManager(tempDir, setups, vertx, ctx.completing(), agentPool)
  }

  /**
   * Deploy the [CloudManager] verticle and call the given [completionHandler]
   */
  private fun deployCloudManager(tempDir: Path, setups: List<Setup>,
      vertx: Vertx, completionHandler: Handler<AsyncResult<String>>,
      agentPool: JsonArray = JsonArray(), sshUsername: String? = "user") {
    // create setups file
    val tempDirFile = tempDir.toRealPath().toFile()
    val setupFile = File(tempDirFile, "test_setups.yaml")
    YamlUtils.mapper.writeValue(setupFile, setups)

    GlobalScope.launch(vertx.dispatcher()) {
      // deploy verticle under test
      val config = json {
        obj(
            ConfigConstants.CLOUD_CREATED_BY_TAG to CREATED_BY_TAG,
            ConfigConstants.CLOUD_SSH_USERNAME to sshUsername,
            ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION to "myprivatekey.pem",
            ConfigConstants.CLOUD_SETUPS_FILE to setupFile.toString(),
            ConfigConstants.CLOUD_SYNC_INTERVAL to SYNC_INTERVAL,
            ConfigConstants.CLOUD_KEEP_ALIVE_INTERVAL to KEEP_ALIVE_INTERVAL,
            ConfigConstants.CLOUD_AGENTPOOL to agentPool
        )
      }
      val options = DeploymentOptions(config)
      cloudManager = CloudManager()
      vertx.deployVerticle(cloudManager, options, completionHandler)
    }
  }

  private suspend fun doCreateOnDemand(setup: Setup, vertx: Vertx,
      ctx: VertxTestContext, mockCreateResources: Boolean = true,
      requiredCapabilities: List<String> = setup.providedCapabilities) {
    val metadata = mapOf(
        "Created-By" to CREATED_BY_TAG,
        "Setup-Id" to setup.id
    )

    coEvery { client.getImageID(setup.imageName) } returns setup.imageName
    if (mockCreateResources) {
      coEvery { client.createBlockDevice(setup.imageName, setup.blockDeviceSizeGb,
          setup.blockDeviceVolumeType, setup.availabilityZone,
          metadata) } answers { UniqueID.next() }
      coEvery { client.createVM(any(), setup.flavor, any(), setup.availabilityZone,
          metadata) } answers { UniqueID.next() }
    }
    coEvery { client.getIPAddress(any()) } answers { UniqueID.next() }
    coEvery { client.waitForVM(any()) } just Runs

    var agentId = ""
    val testShSrcSlot = slot<String>()
    val testShDstSlot = slot<String>()
    val executeSlot = slot<String>()
    coEvery { anyConstructed<SSHClient>().uploadFile(capture(testShSrcSlot),
        capture(testShDstSlot)) } answers {
      agentId = File(testShSrcSlot.captured).readText()
    }

    coEvery { anyConstructed<SSHClient>().execute(capture(executeSlot)) } coAnswers {
      if (executeSlot.captured != "sudo ${testShDstSlot.captured}") {
        ctx.verify {
          assertThat(executeSlot.captured).isEqualTo("sudo chmod +x ${testShDstSlot.captured}")
        }
      } else {
        ctx.verify {
          assertThat(agentId).isNotEmpty()
        }
        vertx.eventBus().publish(REMOTE_AGENT_ADDED,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId)
      }
    }

    cloudManager.createRemoteAgent(requiredCapabilities.toSet())
  }

  /**
   * Test that the cloud manager does not allow duplicate setup IDs
   */
  @Nested
  inner class DuplicateSetupIDs {
    /**
     * Test that the cloud manager does not allow duplicate setup IDs
     */
    @Test
    fun duplicateSetupIDs(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      val testSetup = Setup("test", "myflavor", "myImage", AZ01, 500000, maxVMs = 1)
      val testSetup2 = Setup("test", "myflavor", "myImage", AZ01, 500000, maxVMs = 1)

      deployCloudManager(tempDir, listOf(testSetup, testSetup2), vertx, ctx.failing { t ->
        ctx.verify {
          assertThat(t).isInstanceOf(IllegalArgumentException::class.java)
        }
        ctx.completeNow()
      })
    }
  }

  /**
   * Test that the SSH username can be overridden
   */
  @Nested
  inner class SSHUsername {
    /**
     * Test that the cloud manager accepts an SSH username per setup even if
     * there is no global one
     */
    @Test
    fun perSetup(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      val testSetup = Setup("test", "myflavor", "myImage", AZ01, 500000,
          maxVMs = 1, sshUsername = "user1")
      val testSetup2 = Setup("test2", "myflavor", "myImage", AZ01, 500000,
          maxVMs = 1, sshUsername = "user2")

      deployCloudManager(tempDir, listOf(testSetup, testSetup2), vertx,
          ctx.completing(), sshUsername = null)
    }

    /**
     * Test that the cloud manager fails if there is no global SSH username
     * and at least one of the setups also does not have one
     */
    @Test
    fun missing(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      val testSetup = Setup("test", "myflavor", "myImage", AZ01, 500000,
          maxVMs = 1, sshUsername = "user1")
      val testSetup2 = Setup("test2", "myflavor", "myImage", AZ01, 500000,
          maxVMs = 1)

      deployCloudManager(tempDir, listOf(testSetup, testSetup2), vertx, ctx.failing { t ->
        ctx.verify {
          assertThat(t).isInstanceOf(IllegalArgumentException::class.java)
        }
        ctx.completeNow()
      }, sshUsername = null)
    }
  }

  /**
   * Test that VMs are created
   */
  @Nested
  inner class Create {
    private lateinit var testSetup: Setup
    private lateinit var testSetupLarge: Setup
    private lateinit var testSetupAlternative: Setup
    private lateinit var testSetupTwo: Setup

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      // mock client
      coEvery { client.listVMs(any()) } returns emptyList()

      // create test setups
      val tempDirFile = tempDir.toRealPath().toFile()
      val testSh = File(tempDirFile, "test.sh")
      testSh.writeText("{{ agentId }}")

      testSetup = Setup("test", "myflavor", "myImage", AZ01, 500000,
          null, 0, 1, provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("test1", "foo"))
      testSetupLarge = Setup("testLarge", "myFlavor", "myImage", AZ01, 500000,
          "SSD", 0, 4, provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("test2"))
      testSetupAlternative = Setup("testAlternative", "myAlternativeFlavor",
          "myImage", AZ02, 500000, null, 0, 1,
          provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("test3", "foo"))
      testSetupTwo = Setup("testTwo", "myflavorTwo", "myImageTwo", AZ01, 500000,
          null, 0, 3, maxCreateConcurrent = 2,
          provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("test4", "foo"))

      deployCloudManager(tempDir, listOf(testSetup, testSetupLarge,
          testSetupAlternative, testSetupTwo), vertx, ctx)
    }

    /**
     * Test if a VM with [testSetup] can be created on demand
     */
    @Test
    fun createVMOnDemand(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        doCreateOnDemand(testSetup, vertx, ctx)

        ctx.coVerify {
          coVerify(exactly = 1) {
            client.getImageID(testSetup.imageName)
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb, null,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we can only create one VM with [testSetup] at a time
     */
    @Test
    fun tryCreateTwoAsync(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        val d1 = async { doCreateOnDemand(testSetup, vertx, ctx) }
        val d2 = async { doCreateOnDemand(testSetup, vertx, ctx) }

        d1.await()
        d2.await()

        ctx.coVerify {
          coVerify(exactly = 1) {
            client.getImageID(testSetup.imageName)
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb, null,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we can only create one VM with [testSetup] at all
     */
    @Test
    fun tryCreateTwoSync(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        doCreateOnDemand(testSetup, vertx, ctx)
        doCreateOnDemand(testSetup, vertx, ctx)

        ctx.coVerify {
          coVerify(exactly = 1) {
            client.getImageID(testSetup.imageName)
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb, null,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we can only create four VMs with [testSetupLarge] at all
     */
    @Test
    fun tryCreateFiveSync(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        doCreateOnDemand(testSetupLarge, vertx, ctx)
        doCreateOnDemand(testSetupLarge, vertx, ctx)
        doCreateOnDemand(testSetupLarge, vertx, ctx)
        doCreateOnDemand(testSetupLarge, vertx, ctx)
        doCreateOnDemand(testSetupLarge, vertx, ctx)

        ctx.coVerify {
          coVerify(exactly = 4) {
            client.getImageID(testSetupLarge.imageName)
            client.createBlockDevice(testSetupLarge.imageName,
                testSetupLarge.blockDeviceSizeGb, "SSD",
                testSetupLarge.availabilityZone, any())
            client.createVM(any(), testSetupLarge.flavor, any(),
                testSetupLarge.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Test if a VM with an alternative [testSetup] can be created
     */
    @Test
    fun createVMAlternativeSetup(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        // let the first setup throw an exception and the second succeed
        coEvery { client.createVM(any(), testSetup.flavor, any(),
            testSetup.availabilityZone, any()) } throws IllegalStateException()
        coEvery { client.createVM(any(), testSetupAlternative.flavor, any(),
            testSetupAlternative.availabilityZone, any()) } answers { UniqueID.next() }

        // mock additional methods
        coEvery { client.destroyBlockDevice(any()) } just Runs
        coEvery { client.createBlockDevice(testSetup.imageName,
            testSetup.blockDeviceSizeGb,
            testSetup.blockDeviceVolumeType,
            testSetup.availabilityZone, any()) } answers { UniqueID.next() }
        coEvery { client.createBlockDevice(testSetupAlternative.imageName,
            testSetupAlternative.blockDeviceSizeGb,
            testSetupAlternative.blockDeviceVolumeType,
            testSetupAlternative.availabilityZone, any()) } answers { UniqueID.next() }

        doCreateOnDemand(testSetup, vertx, ctx, false, listOf("foo"))

        ctx.coVerify {
          coVerify(exactly = 2) {
            client.getImageID(testSetup.imageName)
          }
          coVerify(exactly = 1) {
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb, null,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
          }
          coVerify(exactly = 1) {
            client.createBlockDevice(testSetupAlternative.imageName,
                testSetupAlternative.blockDeviceSizeGb, null,
                testSetupAlternative.availabilityZone, any())
            client.createVM(any(), testSetupAlternative.flavor, any(),
                testSetupAlternative.availabilityZone, any())
          }
          coVerify(exactly = 1) {
            client.getIPAddress(any())
            client.destroyBlockDevice(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we can only create two VMs with [testSetupTwo] at a time
     */
    @Test
    fun tryCreateThreeOfTwoAsync(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        val d1 = async { doCreateOnDemand(testSetupTwo, vertx, ctx) }
        val d2 = async { doCreateOnDemand(testSetupTwo, vertx, ctx) }
        val d3 = async { doCreateOnDemand(testSetupTwo, vertx, ctx) }

        d1.await()
        d2.await()
        d3.await()

        ctx.coVerify {
          coVerify(exactly = 2) {
            client.getImageID(testSetupTwo.imageName)
            client.createBlockDevice(testSetupTwo.imageName,
                testSetupTwo.blockDeviceSizeGb, null,
                testSetupTwo.availabilityZone, any())
            client.createVM(any(), testSetupTwo.flavor, any(),
                testSetupTwo.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we can exactly create three VMs with [testSetupTwo]
     */
    @Test
    fun tryCreateThreeOfTwoSync(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        doCreateOnDemand(testSetupTwo, vertx, ctx)
        doCreateOnDemand(testSetupTwo, vertx, ctx)
        doCreateOnDemand(testSetupTwo, vertx, ctx)

        ctx.coVerify {
          coVerify(exactly = 3) {
            client.getImageID(testSetupTwo.imageName)
            client.createBlockDevice(testSetupTwo.imageName,
                testSetupTwo.blockDeviceSizeGb, null,
                testSetupTwo.availabilityZone, any())
            client.createVM(any(), testSetupTwo.flavor, any(),
                testSetupTwo.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that VMs are destroyed
   */
  @Nested
  inner class Destroy {
    private val testSetup = Setup(id = "test", flavor = "myflavor",
        imageName = "myImage", availabilityZone = AZ01,
        blockDeviceSizeGb = 500000, maxVMs = 1)
    private lateinit var vmRegistry: VMRegistry

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      // return a VM that should be deleted when the verticle starts up
      coEvery { client.listVMs(any()) } returns listOf(MY_OLD_VM)
      coEvery { client.isVMActive(MY_OLD_VM) } returns true
      coEvery { client.destroyVM(MY_OLD_VM) } just Runs

      GlobalScope.launch(vertx.dispatcher()) {
        // pretend we already created a VM
        vmRegistry = VMRegistryFactory.create(vertx)
        vmRegistry.addVM(VM(setup = testSetup, status = VM.Status.RUNNING))

        deployCloudManager(tempDir, emptyList(), vertx, ctx)
      }
    }

    /**
     * Check if our old VM has been deleted on startup
     */
    @Test
    fun destroyExistingVM(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          coVerify(exactly = 1) {
            client.destroyVM(MY_OLD_VM)
          }
        }
        ctx.completeNow()
      }
    }

    /**
     * Check if we have updated the registry on startup
     */
    @Test
    fun removeNonExistingVM(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          val vms = vmRegistry.findVMs().toList()
          assertThat(vms).hasSize(1)
          assertThat(vms[0].status).isEqualTo(VM.Status.DESTROYED)
        }
        ctx.completeNow()
      }
    }
  }

  /**
   * Test that a minimum number of VMs is created
   */
  @Nested
  inner class Min {
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var testSetupMin: Setup
    private val keepAliveCounts = mutableMapOf<String, Int>()

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      val tempDirFile = tempDir.toRealPath().toFile()
      val testSh = File(tempDirFile, "test.sh")
      testSh.writeText("{{ agentId }}")

      testSetupMin = Setup("testMin", "myflavor", "myImage", AZ01, 500000,
          null, 2, 4, provisioningScripts = listOf(testSh.absolutePath))

      // mock agent registry
      agentRegistry = mockk()
      mockkObject(AgentRegistryFactory)
      every { AgentRegistryFactory.create(any()) } returns agentRegistry

      val agentIds = mutableSetOf<String>()
      coEvery { agentRegistry.getAgentIds() } returns agentIds

      // mock client
      val createdVMs = mutableListOf<String>()
      coEvery { client.listVMs(any()) } answers { createdVMs }
      coEvery { client.getImageID(testSetupMin.imageName) } returns testSetupMin.imageName
      coEvery { client.createBlockDevice(testSetupMin.imageName,
          testSetupMin.blockDeviceSizeGb, testSetupMin.blockDeviceVolumeType,
          testSetupMin.availabilityZone, any()) } answers { UniqueID.next() }
      coEvery { client.createVM(any(), testSetupMin.flavor, any(),
          testSetupMin.availabilityZone, any()) } answers {
        UniqueID.next().also { createdVMs.add(it) }
      }
      coEvery { client.getIPAddress(any()) } answers { UniqueID.next() }
      coEvery { client.waitForVM(any()) } just Runs

      // mock SSH agent
      val testShSrcSlot = slot<String>()
      val testShDstSlot = slot<String>()
      coEvery { anyConstructed<SSHClient>().execute(any()) } just Runs
      coEvery { anyConstructed<SSHClient>().uploadFile(capture(testShSrcSlot),
          capture(testShDstSlot)) } answers {
        val agentId = File(testShSrcSlot.captured).readText()
        val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

        // count keep-alive messages
        vertx.eventBus().consumer<JsonObject>(address) { msg ->
          val jsonObj: JsonObject = msg.body()
          val action = jsonObj.getString("action")
          if (action == "keepAlive") {
            keepAliveCounts.compute(agentId) { _, n -> (n ?: 0) + 1 }
          }
        }

        // add agent to mock registry
        agentIds.add(agentId)

        // send REMOTE_AGENT_ADDED when the VM has been provisioned
        vertx.eventBus().publish(REMOTE_AGENT_ADDED, address)
      }

      deployCloudManager(tempDir, listOf(testSetupMin), vertx, ctx)
    }

    /**
     * Make sure we always create at least two VMs with [testSetupMin]
     */
    @Test
    fun tryCreateMin(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        // give the CloudManager enough time to call sync() at least three times
        delay(SYNC_INTERVAL * 4 * 1000L)

        ctx.coVerify {
          coVerify(exactly = 2) {
            client.getImageID(testSetupMin.imageName)
            client.createBlockDevice(testSetupMin.imageName,
                testSetupMin.blockDeviceSizeGb,
                testSetupMin.blockDeviceVolumeType,
                testSetupMin.availabilityZone, any())
            client.createVM(any(), testSetupMin.flavor, any(),
                testSetupMin.availabilityZone, any())
            client.getIPAddress(any())
          }

          // check that keep-alive messages have been sent to two remote agents
          assertThat(keepAliveCounts).hasSize(2)
        }

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that agent pool parameters are considered
   */
  @Nested
  inner class AgentPool {
    private lateinit var agentRegistry: AgentRegistry
    private lateinit var testSetup: Setup
    private lateinit var testSetup2: Setup
    private lateinit var testSetup3: Setup
    private val keepAliveCounts = mutableMapOf<String, Int>()

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      // mock agent registry
      agentRegistry = mockk()
      mockkObject(AgentRegistryFactory)
      every { AgentRegistryFactory.create(any()) } returns agentRegistry

      val agentIds = mutableSetOf<String>()
      coEvery { agentRegistry.getAgentIds() } returns agentIds

      // mock client
      coEvery { client.listVMs(any()) } returns emptyList()

      // create test setups
      val tempDirFile = tempDir.toRealPath().toFile()
      val testSh = File(tempDirFile, "test.sh")
      testSh.writeText("{{ agentId }}")

      testSetup = Setup("test", "myflavor", "myImage", AZ01, 500000,
          null, 0, 100, maxCreateConcurrent = 100,
          provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("foo"))
      testSetup2 = Setup("test2", "myflavor2", "myImage2", AZ02, 500000,
          null, 0, 100, maxCreateConcurrent = 100,
          provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("foo", "bar"))
      testSetup3 = Setup("test3", "myflavor3", "myImage3", AZ02, 500000,
          null, 0, 100, maxCreateConcurrent = 100,
          provisioningScripts = listOf(testSh.absolutePath),
          providedCapabilities = listOf("test"))

      // mock client
      val createdVMs = mutableListOf<String>()
      coEvery { client.listVMs(any()) } answers { createdVMs }
      coEvery { client.getImageID(testSetup.imageName) } returns testSetup.imageName
      coEvery { client.getImageID(testSetup2.imageName) } returns testSetup2.imageName
      coEvery { client.getImageID(testSetup3.imageName) } returns testSetup3.imageName
      coEvery { client.createBlockDevice(testSetup.imageName,
          testSetup.blockDeviceSizeGb, testSetup.blockDeviceVolumeType,
          testSetup.availabilityZone, any()) } answers { UniqueID.next() }
      coEvery { client.createBlockDevice(testSetup2.imageName,
          testSetup2.blockDeviceSizeGb, testSetup2.blockDeviceVolumeType,
          testSetup2.availabilityZone, any()) } answers { UniqueID.next() }
      coEvery { client.createBlockDevice(testSetup3.imageName,
          testSetup3.blockDeviceSizeGb, testSetup3.blockDeviceVolumeType,
          testSetup3.availabilityZone, any()) } answers { UniqueID.next() }
      coEvery { client.createVM(any(), testSetup.flavor, any(),
          testSetup.availabilityZone, any()) } answers {
        UniqueID.next().also { createdVMs.add(it) }
      }
      coEvery { client.createVM(any(), testSetup2.flavor, any(),
          testSetup2.availabilityZone, any()) } answers {
        UniqueID.next().also { createdVMs.add(it) }
      }
      coEvery { client.createVM(any(), testSetup3.flavor, any(),
          testSetup3.availabilityZone, any()) } answers {
        UniqueID.next().also { createdVMs.add(it) }
      }
      coEvery { client.getIPAddress(any()) } answers { UniqueID.next() }
      coEvery { client.waitForVM(any()) } just Runs

      // mock SSH agent
      val testShSrcSlot = slot<String>()
      val testShDstSlot = slot<String>()
      coEvery { anyConstructed<SSHClient>().execute(any()) } just Runs
      coEvery { anyConstructed<SSHClient>().uploadFile(capture(testShSrcSlot),
          capture(testShDstSlot)) } answers {
        val agentId = File(testShSrcSlot.captured).readText()
        val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

        // count keep-alive messages
        vertx.eventBus().consumer<JsonObject>(address) { msg ->
          val jsonObj: JsonObject = msg.body()
          val action = jsonObj.getString("action")
          if (action == "keepAlive") {
            keepAliveCounts.compute(agentId) { _, n -> (n ?: 0) + 1 }
          }
        }

        // add agent to mock registry
        agentIds.add(agentId)

        // send REMOTE_AGENT_ADDED when the VM has been provisioned
        vertx.eventBus().publish(REMOTE_AGENT_ADDED, address)
      }

      deployCloudManager(tempDir, listOf(testSetup, testSetup2, testSetup3),
          vertx, ctx, json {
        array(
            obj(
                "capabilities" to array("foo"),
                "min" to 2,
                "max" to 3
            ),
            obj(
                "capabilities" to array("bar"),
                "min" to 1,
                "max" to 2
            ),
            obj(
                "capabilities" to array("test"),
                "min" to 1,
                "max" to 4
            )
        )
      })
    }

    /**
     * Make sure we always create at least a remote agent with required
     * capabilities ["test"] with setup [testSetup3]
     */
    @Test
    fun tryCreateMin(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        // give the CloudManager enough time to call sync() at least three times
        delay(SYNC_INTERVAL * 4 * 1000L)

        ctx.coVerify {
          coVerify(exactly = 1) {
            client.getImageID(testSetup3.imageName)
            client.createBlockDevice(testSetup3.imageName,
                testSetup3.blockDeviceSizeGb,
                testSetup3.blockDeviceVolumeType,
                testSetup3.availabilityZone, any())
            client.createVM(any(), testSetup3.flavor, any(),
                testSetup3.availabilityZone, any())
          }

          // check that keep-alive messages have been sent to all remote agents
          assertThat(keepAliveCounts).hasSize(3)
        }

        ctx.completeNow()
      }
    }

    /**
     * Make sure we always create at least two remote agents with required
     * capabilities ["foo"] (one with setup [testSetup] and another one with
     * setup [testSetup2]) and that we do not create [testSetup2] too often
     * even if we also require ["bar"]
     */
    @Test
    fun tryCreateMinTwo(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        // give the CloudManager enough time to call sync() at least three times
        delay(SYNC_INTERVAL * 4 * 1000L)

        ctx.coVerify {
          coVerify(exactly = 1) {
            client.getImageID(testSetup.imageName)
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb,
                testSetup.blockDeviceVolumeType,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
          }
          coVerify(exactly = 1) {
            client.getImageID(testSetup2.imageName)
            client.createBlockDevice(testSetup2.imageName,
                testSetup2.blockDeviceSizeGb,
                testSetup2.blockDeviceVolumeType,
                testSetup2.availabilityZone, any())
            client.createVM(any(), testSetup2.flavor, any(),
                testSetup2.availabilityZone, any())
          }

          // check that keep-alive messages have been sent to all remote agents
          assertThat(keepAliveCounts).hasSize(3)
        }

        ctx.completeNow()
      }
    }

    private fun tryCreateMax(setup: Setup, max: Int, vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        val ds = (1..(max + 1)).map {
          async { doCreateOnDemand(setup, vertx, ctx) }
        }
        ds.forEach { it.await() }

        ctx.coVerify {
          coVerify(exactly = max) {
            client.getImageID(setup.imageName)
            client.createBlockDevice(setup.imageName,
                setup.blockDeviceSizeGb, null,
                setup.availabilityZone, any())
            client.createVM(any(), setup.flavor, any(),
                setup.availabilityZone, any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }

    /**
     * Check if we can only create 4 VMs having the capabilities ["test"]
     */
    @Test
    fun tryCreateMaxFour(vertx: Vertx, ctx: VertxTestContext) {
      tryCreateMax(testSetup3, 4, vertx, ctx)
    }

    /**
     * Check if we can only create three VMs having the capabilities ["foo"]
     */
    @Test
    fun tryCreateMaxThree(vertx: Vertx, ctx: VertxTestContext) {
      tryCreateMax(testSetup, 3, vertx, ctx)
    }

    /**
     * Check if we can only create two VMs having the capabilities ["foo", "bar"]
     */
    @Test
    fun tryCreateMaxTwo(vertx: Vertx, ctx: VertxTestContext) {
      tryCreateMax(testSetup2, 2, vertx, ctx)
    }

    /**
     * Check if we can create two VMs with capabilities ["foo"] and then only
     * one more with ["foo", "bar"] because the maximum number of foo's is
     * three.
     */
    @Test
    fun tryCreateMaxFooBar(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        val d1 = async { doCreateOnDemand(testSetup, vertx, ctx) }
        val d2 = async { doCreateOnDemand(testSetup, vertx, ctx) }
        val d3 = async { doCreateOnDemand(testSetup2, vertx, ctx) }
        val d4 = async { doCreateOnDemand(testSetup2, vertx, ctx) }

        d1.await()
        d2.await()
        d3.await()
        d4.await()

        ctx.coVerify {
          coVerify(exactly = 2) {
            client.getImageID(testSetup.imageName)
            client.createBlockDevice(testSetup.imageName,
                testSetup.blockDeviceSizeGb, null,
                testSetup.availabilityZone, any())
            client.createVM(any(), testSetup.flavor, any(),
                testSetup.availabilityZone, any())
          }
          coVerify(exactly = 1) {
            client.getImageID(testSetup2.imageName)
            client.createBlockDevice(testSetup2.imageName,
                testSetup2.blockDeviceSizeGb, null,
                testSetup2.availabilityZone, any())
            client.createVM(any(), testSetup2.flavor, any(),
                testSetup2.availabilityZone, any())
          }
          coVerify(exactly = 3) {
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }
  }
}
