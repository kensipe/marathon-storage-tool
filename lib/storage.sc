#!/usr/bin/env amm-2.11

import scala.annotation.tailrec

import akka.actor.{ ActorSystem, ActorRefFactory, Scheduler }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, Sink}
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage._
import mesosphere.marathon.storage.migration.StorageVersions
import mesosphere.marathon.storage.repository.AppRepository
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.rogach.scallop.ScallopOption
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Await}

object Env {
  implicit val actorSystem = ActorSystem()
  implicit val actorMaterializer = ActorMaterializer()
  implicit val scheduler: Scheduler = actorSystem.scheduler

  implicit val timeout = Timeout(5.seconds)

  def await[T](f: Future[T])(implicit timeout: Timeout): T = {
    Await.result(f, timeout.duration)
  }

  def await[T](s: Source[T, Any])(implicit timeout: Timeout): Seq[T] = {
    val f: Future[Seq[T]] = s.completionTimeout(timeout.duration).runWith(Sink.seq[T])
    await(f)
  }

  trait StringFormatter[T] extends (T => String) { def apply(v: T): String }
  object StringFormatter {
    def apply[T](fn: T => String): StringFormatter[T] = new StringFormatter[T] {
      override def apply(v: T): String = fn(v)
    }
  }
  implicit val InstanceIdFormatter = StringFormatter[Instance.Id] { _.idString }
  implicit val PathIdFormatter = StringFormatter[PathId] { _.toString }
  class QueryResult[T](val values: Seq[T])(implicit formatter: StringFormatter[T]) {
    def formattedValues: Seq[String] = values.map(formatter)
    override def toString(): String = {
      val b = new java.lang.StringBuilder
      b.append("Results:\n\n")
      formattedValues.foreach { v =>
        b.append("  ")
        b.append(v)
        b.append('\n')
      }
      b.toString
    }
  }
  object QueryResult {
    def apply[T](values: Seq[T])(implicit formatter: StringFormatter[T]) = new QueryResult[T](values)
  }

  implicit def stringToPathId(s: String): PathId = PathId(s)

  def listApps(containing: String = null, limit: Int = Int.MaxValue)(
    implicit module: StorageModule, timeout: Timeout): QueryResult[PathId] = {
    val predicates = List(
      Option(containing).map { c => { pathId: PathId => pathId.toString.contains(c) } }
    ).flatten

    QueryResult {
      await(module.appRepository.ids)
        .filter { app =>
          predicates.forall { p => p(app) }
        }
        .sorted
        .take(limit)
    }
  }

  def listInstances(
    forApp: PathId = null,
    containing: String = null,
    limit: Int = Int.MaxValue)(
    implicit module: StorageModule, timeout: Timeout): QueryResult[Instance.Id] = {
    val predicates: List[(Instance.Id => Boolean)] = List(
      Option(containing).map { c =>
        { instanceId: Instance.Id => instanceId.toString.contains(c) }
      }
    ).flatten

    val input = Option(forApp) match {
      case Some(appId) =>
        module.instanceRepository.instances(appId)
      case None =>
        module.instanceRepository.ids
    }
    QueryResult {
      await(input)
        .filter { instanceId =>
          predicates.forall { p => p(instanceId) }
        }
        .sorted
        .take(limit)
    }
  }

  trait PurgeStrategy[T] {
    val purgeDescription: String
    def `purge!`(values: Seq[T]): Unit
  }

  def getRWAppRepository(implicit module: StorageModule): Option[AppRepository] = {
    module.appRepository match {
      case a: AppRepository => Some(a)
      case _ => None
    }
  }

  implicit def UnwrapQueryResult[T](qr: QueryResult[T]): Seq[T] = qr.values
  implicit def InstancePurgeStrategy(implicit module: StorageModule): PurgeStrategy[Instance.Id] = new PurgeStrategy[Instance.Id] {
    val purgeDescription = "instances"
    override def `purge!`(values: Seq[Instance.Id]): Unit = {
      values.foreach { v =>
        module.instanceRepository.delete(v)
        println(s"Purged instance: ${InstanceIdFormatter(v)}")
      }
    }
  }
  implicit def AppPurgeStrategy(implicit module: StorageModule): PurgeStrategy[PathId] = new PurgeStrategy[PathId] {
    val purgeDescription = "apps and associated instances"
    override def `purge!`(values: Seq[PathId]): Unit = {
      // Remove from rootGroup
      val rootGroup = await(module.groupRepository.root)
      val newGroup = values.foldLeft(rootGroup) { (r, value) => r.removeApp(value) }
      module.groupRepository.storeRoot(newGroup, Nil, deletedApps = values, updatedPods = Nil, deletedPods = Nil)
      println(s"Removed ${values.map(PathIdFormatter).toList} from root group")

      getRWAppRepository match {
        case Some(appRepo) =>
          values.foreach { appId =>
            val instances = await(module.instanceRepository.instances(appId))
            InstancePurgeStrategy.`purge!`(instances)
            appRepo.delete(appId)
            println(s"Purged app ${appId}")
          }
        case None =>
          println(s"Error removing apps from repository; appRepository instance is not read-writable")
      }
    }
  }

  val NoPendingAction: Int => Unit = { _ => println(s"No pending action for confirmation") }
  var confirm: Int => Unit = NoPendingAction

  def setConfirmation(id: Int)(fn: => Unit): Unit = {
    confirm = { confirmId =>
      if (id!=confirmId)
        println(s"Confirmation ID did not match")
      else {
        confirm = NoPendingAction
        fn
      }
    }
    println(s"To confirm, type:\n\n  confirm(${id})")
  }

  def purge[T](values: Seq[T])(implicit purgeStrategy: PurgeStrategy[T], formatter: StringFormatter[T]): Unit = {
    println()
    println(s"Are you sure you wish to purge the following ${purgeStrategy.purgeDescription}?")
    println()
    val formattedValues = values.map(formatter)
    formattedValues.foreach { v => println(s"  ${v}") }
    println()
    setConfirmation(formattedValues.hashCode) {
      purgeStrategy.`purge!`(values)
      println()
      println("Done")
      println()
      println("Note: The leading Marathon will need to be restarted to see changes")
    }
  }

  def purgeApp(appId: PathId)(implicit module: StorageModule): Unit = {
    purge(List(appId))
  }

  def help: Unit = {
    println(s"""
Marathon State Surgey Tool
==========================

Commands:

  listApps(containing: String, limit: Int)

    description: Return (sorted) list apps in repository

    params:
      containing : List all apps with the specified string in the appId
      limit      : Limit number of apps returned

    example:

      listApps(containing = "store", limit = 5)

  listInstances(forApp: PathId, containing: String, limit: Int)

    description: List instances in repository
    params:
      containing : List all instances containing the specified string in their id
      limit      : Limit number of instances returned
      forApp     : List instances pertaining to the specified appId

    example:

      listInstances(forApp = "/example", limit = 5)

  purge(items: Seq[T])

    description: Purge the specified items
    
    example:

      purge(listInstances(forApp = "/example"))

  purgeApp(appId: PathId)

    description: Purge the specified app

    example:

      purgeApp("/example")

  help

    description: Show this help
""")
  }

  def error(str: String): Nothing = {
    println(s"Error! ${str}")
    sys.exit(1)
    ???
  }
}

object MarathonStorage {
  def argsFromEnv: List[String] = {
    "(?<!\\\\)( +)".r.split(sys.env.getOrElse("MARATHON_ARGS", "").trim).toList.filterNot(_ == "")
  }
}
class MarathonStorage(args: List[String] = MarathonStorage.argsFromEnv) {
  import Env._
  private class ScallopStub[A](name: String, value: Option[A]) extends ScallopOption[A](name) {
    override def get = value
    override def apply() = value.get
  }

  private object ScallopStub {
    def apply[A](value: Option[A]): ScallopStub[A] = new ScallopStub("", value)
    def apply[A](name: String, value: Option[A]): ScallopStub[A] = new ScallopStub(name, value)
  }

  private class MyStorageConf(args: List[String] = Nil, override val availableFeatures: Set[String] = Set.empty) extends org.rogach.scallop.ScallopConf(args) with StorageConf {
    import org.rogach.scallop.exceptions._
    override def onError(e: Throwable): Unit = e match {
      case Help("") =>
        builder.printHelp
        sys.exit(0)
      case e => println(e)
    }

    override lazy val storeCache = ScallopStub(Some(false))
    override lazy val versionCacheEnabled = ScallopStub(Some(false))
  }

  implicit private lazy val metrics: Metrics = new Metrics(new MetricRegistry)
  private val config = new MyStorageConf(args); config.verify
  implicit lazy val storage = StorageConfig(config) match {
    case zk: CuratorZk => zk
  }
  implicit lazy val client = storage.client
  implicit lazy val module = StorageModule(storage, None)

  def assertStoreCompat: Unit = {
    def formattedVersion(v: StorageVersion): String = s"${v.getMajor}.${v.getMinor}.${v.getPatch}"
    val storageVersion = await(storage.store.storageVersion()).getOrElse {
      error(s"Could not determine current storage version!")
    }
    if ((storageVersion.getMajor == StorageVersions.current.getMajor) &&
      (storageVersion.getMinor == StorageVersions.current.getMinor) &&
      (storageVersion.getPatch == StorageVersions.current.getPatch)) {
      println(s"Storage version ${formattedVersion(storageVersion)} matches tool version.")
    } else {
      error(s"Storage version ${formattedVersion(storageVersion)} does not match tool version!")
    }
  }
}
