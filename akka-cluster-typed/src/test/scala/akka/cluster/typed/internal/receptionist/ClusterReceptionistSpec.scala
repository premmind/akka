/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import java.nio.charset.StandardCharsets

import akka.actor.{ ExtendedActorSystem, RootActorPath }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, ActorRefResolver }
import akka.cluster.MemberStatus
import akka.cluster.typed.{ Cluster, Join }
import akka.serialization.SerializerWithStringManifest
import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

object ClusterReceptionistSpec {
  val config = ConfigFactory.parseString(
    s"""
      akka.loglevel = DEBUG # issue #24960
      akka.actor {
        provider = cluster
        serialize-messages = off
        allow-java-serialization = true
        serializers {
          test = "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$PingSerializer"
        }
        serialization-bindings {
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Ping" = test
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Pong$$" = test
          "akka.cluster.typed.internal.receptionist.ClusterReceptionistSpec$$Perish$$" = test
        }
      }
      akka.remote.artery.enabled = true
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1
      akka.cluster {
        auto-down-unreachable-after = 0s
        jmx.multi-mbeans-in-same-jvm = on
      }
    """)

  case object Pong
  trait PingProtocol
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol
  case object Perish extends PingProtocol

  val pingPongBehavior = Behaviors.receive[PingProtocol] { (_, msg) ⇒
    msg match {
      case Ping(respondTo) ⇒
        respondTo ! Pong
        Behaviors.same

      case Perish ⇒
        Behaviors.stopped
    }
  }

  class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 47
    def manifest(o: AnyRef): String = o match {
      case _: Ping ⇒ "a"
      case Pong    ⇒ "b"
      case Perish  ⇒ "c"
    }

    def toBinary(o: AnyRef): Array[Byte] = o match {
      case p: Ping ⇒ ActorRefResolver(system.toTyped).toSerializationFormat(p.respondTo).getBytes(StandardCharsets.UTF_8)
      case Pong    ⇒ Array.emptyByteArray
      case Perish  ⇒ Array.emptyByteArray
    }

    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
      case "a" ⇒ Ping(ActorRefResolver(system.toTyped).resolveActorRef(new String(bytes, StandardCharsets.UTF_8)))
      case "b" ⇒ Pong
      case "c" ⇒ Perish
    }
  }

  val PingKey = ServiceKey[PingProtocol]("pingy")
}

class ClusterReceptionistSpec extends WordSpec with Matchers {

  import ClusterReceptionistSpec._
  import Receptionist._

  "The cluster receptionist" must {

    "eventually replicate registrations to the other side" in {
      val testKit1 = new ActorTestKit {
        override def name = super.name + "-test-1"
        override def config = ClusterReceptionistSpec.config
      }
      val system1 = testKit1.system
      val testKit2 = new ActorTestKit {
        override def name = system1.name
        override def config = testKit1.system.settings.config
      }
      val system2 = testKit2.system
      try {
        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)
        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) == 2)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service = testKit1.spawn(pingPongBehavior)
        testKit1.system.receptionist ! Register(PingKey, service, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service))

        val PingKey.Listing(remoteServiceRefs) = regProbe2.expectMessageType[Listing]
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe2.ref)
        regProbe2.expectMessage(Pong)

        service ! Perish
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "remove registrations when node dies" in {
      val testKit1 = new ActorTestKit {
        override def name = super.name + "-test-2"
        override def config = ClusterReceptionistSpec.config
      }
      val system1 = testKit1.system
      val testKit2 = new ActorTestKit {
        override def name = system1.name
        override def config = testKit1.system.settings.config
      }
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) == 2)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service2 = testKit2.spawn(pingPongBehavior)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        val remoteServiceRefs = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe1.ref)
        regProbe1.expectMessage(Pong)

        // abrupt termination
        system2.terminate()
        regProbe1.expectMessage(10.seconds, Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
      } finally {
        testKit1.shutdownTestKit()
        if (!system1.whenTerminated.isCompleted) testKit2.shutdownTestKit()
      }
    }

    "work with services registered before node joins cluster" in {
      val testKit1 = new ActorTestKit {
        override def name = super.name + "-test-2"
        override def config = ClusterReceptionistSpec.config
      }
      val system1 = testKit1.system
      val testKit2 = new ActorTestKit {
        override def name = system1.name
        override def config = testKit1.system.settings.config
      }
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) == 2)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service2 = testKit2.spawn(pingPongBehavior)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        // then we join the cluster
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)
        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up))

        // and the subscriber on node1 should see the service
        val remoteServiceRefs = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe1.ref)
        regProbe1.expectMessage(Pong)

        // abrupt termination
        system2.terminate()
        regProbe1.expectMessage(10.seconds, Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
      } finally {
        testKit1.shutdownTestKit()
        if (!system1.whenTerminated.isCompleted) testKit2.shutdownTestKit()
      }
    }

    "handle a new incarnation of the same node well" in {
      val testKit1 = new ActorTestKit {
        override def name = super.name + "-test-3"
        override def config = ClusterReceptionistSpec.config
      }
      val system1 = testKit1.system
      val testKit2 = new ActorTestKit {
        override def name = system1.name
        override def config = testKit1.system.settings.config
      }
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) == 2)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service2 = testKit2.spawn(pingPongBehavior, "instance")
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        // make sure we saw the first incarnation on node1
        val remoteServiceRefs = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe1.ref)
        regProbe1.expectMessage(Pong)

        // FIXME do we need to blackhole the connection to system2 before terminating
        // right now it doesn't work anyways though ;D

        // abrupt termination but then a node with the same host:port comes online quickly
        system1.log.debug("Terminating system2, uid: [{}]", clusterNode2.selfMember.uniqueAddress.longUid)
        Await.ready(system2.terminate(), 10.seconds)

        val testKit3 = new ActorTestKit {
          override protected def name: String = system1.name
          override def config: Config = testKit1.config
        }
        try {
          val system3 = testKit3.system
          system1.log.debug("Starting system3 at same hostname port as system2, uid: [{}]", Cluster(system3).selfMember.uniqueAddress.longUid)
          val clusterNode3 = Cluster(system3)
          clusterNode3.manager ! Join(clusterNode1.selfMember.address)
          val regProbe3 = TestProbe[Any]()(system3)

          // and registers the same service key
          val service3 = testKit3.spawn(pingPongBehavior, "instance")
          system3.log.debug("Spawning/registering ping service in new incarnation {}#{}", service3.path, service3.path.uid)
          system3.receptionist ! Register(PingKey, service3, regProbe3.ref)
          regProbe3.expectMessage(Registered(PingKey, service3))
          system3.log.debug("Registered actor [{}#{}] for system3", service3.path, service3.path.uid)

          // make sure it joined fine and node1 has upped it
          regProbe1.awaitAssert {
            clusterNode1.state.members.exists(m ⇒
              m.uniqueAddress == clusterNode3.selfMember.uniqueAddress &&
                m.status == MemberStatus.Up &&
                !clusterNode1.state.unreachable(m)
            )
          }

          // we should get either empty message and then updated with the new incarnation actor
          // or just updated with the new service directly
          val msg = regProbe1.fishForMessage(20.seconds) {
            case PingKey.Listing(entries) if entries.size == 1 ⇒ FishingOutcome.Complete
            case _: Listing                                    ⇒ FishingOutcome.ContinueAndIgnore
          }
          val PingKey.Listing(entries) = msg.last
          entries should have size 1
          val ref = entries.head
          val service3RemotePath = RootActorPath(clusterNode3.selfMember.address) / "user" / "instance"
          ref.path should ===(service3RemotePath)
          ref ! Ping(regProbe1.ref)
          regProbe1.expectMessage(Pong)

        } finally {
          testKit3.shutdownTestKit()
        }
      } finally {
        testKit1.shutdownTestKit()
        if (!system1.whenTerminated.isCompleted) testKit2.shutdownTestKit()
      }
    }

  }
}
