/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.util.concurrent.TimeUnit.NANOSECONDS

import akka.actor._
import akka.cluster.MemberStatus
import akka.testkit._
import akka.util.ccompat._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

@ccompatUsedUntil213
object ClusterShardingRememberEntitiesPerfSpec {

  def props(): Props = Props(new TestEntity)

  class TestEntity extends Actor with ActorLogging {

    log.debug("Started TestEntity: {}", self)

    def receive = {
      case m => sender() ! m
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case _: Int                     => "0" // only one shard
      case ShardRegion.StartEntity(_) => "0"
    }

}

object ClusterShardingRememberEntitiesPerfSpecConfig extends MultiNodeClusterShardingConfig(additionalConfig = s"""
    akka.testconductor.barrier-timeout = 3 minutes
    akka.remote.artery.advanced.outbound-message-queue-size = 10000
    akka.remote.artery.advanced.maximum-frame-size = 512 KiB
    # comment next line to enable durable lmdb storage
    akka.cluster.sharding.distributed-data.durable.keys = []
    """) {

  val first = role("first")
  val second = role("second")
  val third = role("third")

  nodeConfig(third)(ConfigFactory.parseString(s"""
    akka.cluster.sharding.distributed-data.durable.lmdb {
      # use same directory when starting new node on third (not used at same time)
      dir = "$targetDir/sharding-third"
    }
    """))
}

class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode1 extends ClusterShardingRememberEntitiesPerfSpec
class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode2 extends ClusterShardingRememberEntitiesPerfSpec
class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode3 extends ClusterShardingRememberEntitiesPerfSpec

abstract class ClusterShardingRememberEntitiesPerfSpec
    extends MultiNodeClusterShardingSpec(ClusterShardingRememberEntitiesPerfSpecConfig)
    with ImplicitSender {
  import ClusterShardingRememberEntitiesPerfSpec._
  import ClusterShardingRememberEntitiesPerfSpecConfig._

  def startSharding(): Unit = {
    (1 to 3).foreach { n =>
      startSharding(
        system,
        typeName = s"Entity$n",
        entityProps = ClusterShardingRememberEntitiesPerfSpec.props(),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
    }
  }

  lazy val region1 = ClusterSharding(system).shardRegion("Entity1")
  lazy val region2 = ClusterSharding(system).shardRegion("Entity2")
  lazy val region3 = ClusterSharding(system).shardRegion("Entity3")

  // use 5 for "real" testing
  private val nrIterations = 2
  // use 5 for "real" testing
  private val numberOfMessagesFactor = 1

  s"Cluster sharding with remember entities performance" must {

    "form cluster" in within(20.seconds) {
      join(first, first)

      startSharding()

      // this will make it run on first
      runOn(first) {
        region1 ! 0
        expectMsg(0)
        region2 ! 0
        expectMsg(0)
        region3 ! 0
        expectMsg(0)
      }
      enterBarrier("allocated-on-first")

      join(second, first)
      join(third, first)

      within(remaining) {
        awaitAssert {
          cluster.state.members.size should ===(3)
          cluster.state.members.unsorted.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }

      enterBarrier("all-up")
    }

    "test when starting new entity" in {
      runOn(first) {
        val numberOfMessages = 200 * numberOfMessagesFactor
        (1 to nrIterations).foreach { iteration =>
          val startTime = System.nanoTime()
          (1 to numberOfMessages).foreach { n =>
            region1 ! (iteration * 100000 + n)
          }
          receiveN(numberOfMessages, 20.seconds)
          val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
          val throughput = numberOfMessages * 1000.0 / took
          println(
            s"### Test1 with $numberOfMessages took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, " +
            f"throughput $throughput%,.0f msg/s")
        }
      }
      enterBarrier("after-1")
    }

    "test when starting new entity and sending a few messages to it" in {
      runOn(first) {
        val numberOfMessages = 800 * numberOfMessagesFactor
        (1 to nrIterations).foreach { iteration =>
          val startTime = System.nanoTime()
          for (n <- 1 to numberOfMessages / 5; _ <- 1 to 5) {
            region2 ! (iteration * 100000 + n)
          }
          receiveN(numberOfMessages, 20.seconds)
          val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
          val throughput = numberOfMessages * 1000.0 / took
          println(
            s"### Test2 with $numberOfMessages took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, " +
            f"throughput $throughput%,.0f msg/s")
        }
      }
      enterBarrier("after-2")
    }

    "test when starting some new entities mixed with sending to started" in {
      runOn(first) {
        val numberOfMessages = 1600 * numberOfMessagesFactor
        (1 to nrIterations).foreach { iteration =>
          val startTime = System.nanoTime()
          (1 to numberOfMessages).foreach { n =>
            val msg =
              if (n % 20 == 0)
                -(iteration * 100000 + n) // unique, will start new entity
              else
                iteration * 100000 + (n % 10) // these will go to same 10 started entities
            region3 ! msg

            if (n == 10) {
              // wait for the first 10 to avoid filling up stash
              receiveN(10, 5.seconds)
            }
          }
          receiveN(numberOfMessages - 10, 20.seconds)
          val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
          val throughput = numberOfMessages * 1000.0 / took
          println(
            s"### Test3 with $numberOfMessages took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, " +
            f"throughput $throughput%,.0f msg/s")
        }
      }
      enterBarrier("after-3")
    }

    "test sending to started" in {
      runOn(first) {
        val numberOfMessages = 1600 * numberOfMessagesFactor
        (1 to nrIterations).foreach { iteration =>
          var startTime = System.nanoTime()
          (1 to numberOfMessages).foreach { n =>
            region3 ! (iteration * 100000 + (n % 10)) // these will go to same 10 started entities

            if (n == 10) {
              // wait for the first 10 and then start the clock
              receiveN(10, 5.seconds)
              startTime = System.nanoTime()
            }
          }
          receiveN(numberOfMessages - 10, 20.seconds)
          val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
          val throughput = numberOfMessages * 1000.0 / took
          println(
            s"### Test4 with $numberOfMessages took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, " +
            f"throughput $throughput%,.0f msg/s")
        }
      }
      enterBarrier("after-4")
    }
  }

}
