package cloud

import coVerify
import db.VMRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.cloud.PoolAgentParams
import model.cloud.VM
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [SetupSelector]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class SetupSelectorTest {
  companion object {
    private const val RQ1 = "a"
    private const val RQ2 = "b"
    private val SETUP01 = Setup("setup1", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 1, providedCapabilities = listOf(RQ1))
    private val SETUP02 = Setup("setup2", "myOtherFlavor", "myOtherImage", "az-02",
        100000, maxVMs = 1, providedCapabilities = listOf(RQ2))
    private val SETUP03 = Setup("setup3", "myFlavor", "myImage", "az-03",
        300000, maxVMs = 1, providedCapabilities = listOf(RQ1))
    private val SETUP04 = Setup("setup4", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, providedCapabilities = listOf(RQ1))
    private val SETUP05 = Setup("setup5", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, providedCapabilities = listOf(RQ1))
    private val SETUP06 = Setup("setup6", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, maxCreateConcurrent = 3, providedCapabilities = listOf(RQ1))
    private val SETUP07 = Setup("setup7", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 5, maxCreateConcurrent = 5, providedCapabilities = listOf(RQ1))
    private val SETUP08 = Setup("setup8", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 5, maxCreateConcurrent = 5, providedCapabilities = listOf(RQ1, RQ2))
    private val SETUP09 = Setup("setup9", "myFlavor", "myImage", "az-01",
        500000, minVMs = 2, maxVMs = 5, maxCreateConcurrent = 5, providedCapabilities = listOf(RQ2))
    private val SETUP10 = Setup("setup10", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 100, maxCreateConcurrent = 3, providedCapabilities = listOf(RQ1))
    private val SETUP11 = Setup("setup11", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 100, maxCreateConcurrent = 3, providedCapabilities = listOf(RQ1))
  }

  /**
   * Select setups by required capabilities
   */
  @Test
  fun selectByCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(any()) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, emptyList(), emptyList())).isEmpty()
        assertThat(selector.select(1, listOf(RQ1), listOf(SETUP01, SETUP02)))
            .containsExactly(SETUP01)
        assertThat(selector.select(1, listOf(RQ2), listOf(SETUP01, SETUP02)))
            .containsExactly(SETUP02)
        assertThat(selector.select(1, listOf(RQ1, RQ2), listOf(SETUP01, SETUP02)))
            .isEmpty()
        assertThat(selector.select(1, listOf(RQ1), listOf(SETUP01, SETUP02, SETUP03)))
            .containsExactly(SETUP01)
        assertThat(selector.select(2, listOf(RQ1), listOf(SETUP01, SETUP02, SETUP03)))
            .containsExactly(SETUP01, SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if there already is an existing VM
   */
  @Test
  fun dontSelectExistingVM(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .containsExactly(SETUP03)
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if a matching VM is currently starting
   */
  @Test
  fun dontSelectStartingVM(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if a matching VM already exists or is currently starting
   */
  @Test
  fun dontSelectMixed(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP04.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP05.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP04.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP05.id) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03, SETUP04, SETUP05)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP04)
        assertThat(selector.select(3, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP05)
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP05)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if `maxConcurrent` is exceeded
   */
  @Test
  fun maxConcurrent(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP04.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP05.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP06.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP04.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP05.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP06.id) } returns 1

    val setups = listOf(SETUP01, SETUP02, SETUP03, SETUP04, SETUP05, SETUP06)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(3, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP04)
        assertThat(selector.select(5, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06)
        assertThat(selector.select(6, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06, SETUP06)
        assertThat(selector.select(10, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06, SETUP06)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if the maximum number of agents is exceeded
   */
  @Test
  fun maxAgents(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(PoolAgentParams(listOf(RQ1), max = 4)))

    coEvery { vmRegistry.findNonTerminatedVMs() } returns listOf(VM(setup = SETUP07), VM(setup = SETUP07))
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP07.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP07.id) } returns 1

    val setups = listOf(SETUP07)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP07)
        assertThat(selector.select(3, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
        assertThat(selector.select(10, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
      }
      ctx.completeNow()
    }
  }

  /**
   * Configure two capability sets ([RQ1] and [RQ1] + [RQ2]) in the agent pool
   * and then check if the correct number of setups is returned. The first set
   * has a maximum of 3 and the second a maximum of 1. If we request 5 setups
   * for [RQ1], only 3 should be returned (3 with [RQ1] and none with [RQ2])
   */
  @Test
  fun maxAgentsTwoReqCapSets(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), max = 3), PoolAgentParams(listOf(RQ1, RQ2), max = 1)))

    coEvery { vmRegistry.findNonTerminatedVMs() } returns emptyList()
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(any()) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    val setups = listOf(SETUP07, SETUP08)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(5, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07, SETUP07)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a minimum number of VMs can be created based on agent pool params
   */
  @Test
  fun selectMinimum(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), min = 2),
        PoolAgentParams(listOf(RQ1, RQ2), min = 1)))

    val setups = listOf(SETUP07, SETUP08)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.selectMinimum(setups, false))
            .containsExactly(SETUP07, SETUP07, SETUP08)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that only two VMs will be created if the pool agent params overlap
   * and one of them has a maximum of 2.
   */
  @Test
  fun selectMinimumOverlapping(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), min = 2, max = 2),
        PoolAgentParams(listOf(RQ1, RQ2), min = 1)))

    val setups = listOf(SETUP07, SETUP08)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.selectMinimum(setups, false))
            .containsExactly(SETUP07, SETUP07)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that only three VMs will be created if the pool agent params overlap
   * and one of them has a maximum of 3 but only a minimum of 1.
   */
  @Test
  fun selectMinimumOverlappingMore(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), min = 2, max = 3),
        PoolAgentParams(listOf(RQ1, RQ2), min = 2)))

    val setups = listOf(SETUP07, SETUP08)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.selectMinimum(setups, false))
            .containsExactly(SETUP07, SETUP07, SETUP08)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a minimum number of VMs can be created if [Setup.minVMs]
   * has been specified
   */
  @Test
  fun selectMinimumVMs(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    val setups = listOf(SETUP07, SETUP08, SETUP09)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.selectMinimum(setups, false))
            .containsExactly(SETUP09, SETUP09)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a minimum number of VMs can be created if both [Setup.minVMs]
   * and [PoolAgentParams.min] have been specified in various combinations
   */
  @Test
  fun selectMinimumVMsAndParams(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val selector1 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ1), min = 2, max = 3),
            PoolAgentParams(listOf(RQ1, RQ2), min = 2)))
        val setups1 = listOf(SETUP07, SETUP08, SETUP09)
        assertThat(selector1.selectMinimum(setups1, false))
            .containsExactly(SETUP07, SETUP07, SETUP08, SETUP09, SETUP09)

        val selector2 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ2), min = 2)))
        val setups2 = listOf(SETUP07, SETUP09)
        assertThat(selector2.selectMinimum(setups2, false))
            .containsExactly(SETUP09, SETUP09)

        val selector3 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ2), min = 1)))
        val setups3 = listOf(SETUP07, SETUP09)
        assertThat(selector3.selectMinimum(setups3, false))
            .containsExactly(SETUP09, SETUP09)

        val selector4 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ2), min = 4)))
        val setups4 = listOf(SETUP07, SETUP09)
        assertThat(selector4.selectMinimum(setups4, false))
            .containsExactly(SETUP09, SETUP09, SETUP09, SETUP09)

        val selector5 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ2), min = 10)))
        val setups5 = listOf(SETUP07, SETUP09)
        assertThat(selector5.selectMinimum(setups5, false))
            .containsExactly(SETUP09, SETUP09, SETUP09, SETUP09, SETUP09)

        val selector6 = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ2), min = 1, max = 1)))
        val setups6 = listOf(SETUP07, SETUP09)
        assertThat(selector6.selectMinimum(setups6, false))
            .containsExactly(SETUP09)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a minimum number of VMs can be created based on agent pool params
   * and that existing VMs are correctly considered
   */
  @Test
  fun selectMinimumExisting(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()

    coEvery { vmRegistry.findNonTerminatedVMs() } returns listOf(
        VM(setup = SETUP07), VM(setup = SETUP08), VM(setup = SETUP08))

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val selector = SetupSelector(vmRegistry, listOf(
            PoolAgentParams(listOf(RQ1), min = 2),
            PoolAgentParams(listOf(RQ1, RQ2), min = 3)))
        val setups = listOf(SETUP07, SETUP08)

        assertThat(selector.selectMinimum(setups, false))
            .containsExactly(SETUP07, SETUP07, SETUP08, SETUP08, SETUP08)

        assertThat(selector.selectMinimum(setups, true))
            .containsExactly(SETUP07, SETUP08)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if the selector still works correctly even if there are too many
   * VMs starting at the moment (the number of VMs starting is greater than
   * `maxCreateConcurrent` for whatever reason)
   */
  @Test
  fun selectWithTooManyStarting(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), min = 0, max = 7)))

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP10.id) } returns 5
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP11.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP10.id) } returns 5
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP11.id) } returns 1
    coEvery { vmRegistry.findNonTerminatedVMs() } returns listOf(
        VM(setup = SETUP10), VM(setup = SETUP10), VM(setup = SETUP10),
        VM(setup = SETUP10), VM(setup = SETUP10), VM(setup = SETUP11))

    val setups = listOf(SETUP10, SETUP11)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        // 6 VMs are already running and the pool agent params define a
        // maximum of 7 so the selector should only return one entry!
        assertThat(selector.select(100, listOf(RQ1), setups))
            .containsExactly(SETUP11)
            .hasSize(1)
      }
      ctx.completeNow()
    }
  }
}
