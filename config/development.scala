import scala.collection.JavaConversions._
import com.twitter.flockdb.config._
import com.twitter.gizzard.config._
import com.twitter.querulous.config._
import com.twitter.querulous.StatsCollector
import com.twitter.conversions.time._
import com.twitter.conversions.storage._
import com.twitter.flockdb.shards.QueryClass
import com.twitter.flockdb.Priority
import com.twitter.flockdb.queries.Query
import com.twitter.flockdb.queries

trait Credentials extends Connection {
  val env = System.getenv().toMap
  val username = env.get("DB_USERNAME").getOrElse("root")
  val password = env.get("DB_PASSWORD").getOrElse("")
}

class ProductionQueryEvaluator extends QueryEvaluator {
  autoDisable = new AutoDisablingQueryEvaluator {
    val errorCount = 100
    val interval = 60.seconds
  }

  database.memoize = true
  database.pool = new ApachePoolingDatabase {
    sizeMin = 40
    sizeMax = 40
    maxWait = 3000.millis
    minEvictableIdle = 60.seconds
    testIdle = 1.second
    testOnBorrow = false
  }

  database.timeout = new TimingOutDatabase {
    open = 3.seconds
    poolSize = 10
    queueSize = 10000
  }

  query.timeouts = Map(
    QueryClass.Select -> QueryTimeout(1.second),
    QueryClass.Execute -> QueryTimeout(1.second),
    QueryClass.SelectCopy -> QueryTimeout(15.seconds),
    QueryClass.SelectModify -> QueryTimeout(3.seconds)
  )

}

class ProductionNameServerReplica(host: String) extends Mysql {
  val connection = new Connection with Credentials {
    val hostnames = Seq(host)
    val database = "flockdb_development"
  }

  queryEvaluator = new ProductionQueryEvaluator {
    database.pool.foreach { p =>
      p.sizeMin = 1
      p.sizeMax = 1
      p.maxWait = 3.seconds
    }

    database.timeout.foreach { t =>
      t.open = 3.seconds
    }
  }
}

new FlockDB {
  aggregateJobsPageSize = 500

  val server = new FlockDBServer with TSelectorServer {
    timeout = 3000.millis
    idleTimeout = 3.minutes
    threadPool.minThreads = 250
    threadPool.maxThreads = 250
  }

  // Added to solve the intersection query problem

  intersectionQuery = new IntersectionQuery {
    intersectionTimeout = 5000.millis
    averageIntersectionProportion = 0.1
    intersectionPageSizeMax = 4000
    
    override def intersect(query1: queries.Query, query2: queries.Query) = new queries.IntersectionQuery(
      query1,
      query2,
      averageIntersectionProportion,
      intersectionPageSizeMax,
      intersectionTimeout
    )

    override def difference(query1: queries.Query, query2: queries.Query) = new queries.DifferenceQuery(
      query1,
      query2,
      averageIntersectionProportion,
      intersectionPageSizeMax,
      intersectionTimeout
    )
  }   

  val nameServer = new com.twitter.gizzard.config.NameServer {
    mappingFunction = ByteSwapper
    jobRelay = NoJobRelay

    val replicas = Seq(
      new ProductionNameServerReplica("localhost")
    )
  }

  jobInjector.timeout = 3000.millis
  jobInjector.idleTimeout = 60.seconds
  jobInjector.threadPool.minThreads = 30

  val replicationFuture = new Future {
    poolSize = 100
    maxPoolSize = 100
    keepAlive = 5.seconds
    timeout = 6.seconds
  }

  val readFuture = new Future {
    poolSize = 100
    maxPoolSize = 100
    keepAlive = 5.seconds
    timeout = 6.seconds
  }

  val databaseConnection = new Credentials {
    val hostnames = Seq("localhost")
    val database = "edges_development"
    urlOptions = Map("rewriteBatchedStatements" -> "true")
  }

  val edgesQueryEvaluator = new ProductionQueryEvaluator

  val materializingQueryEvaluator = new ProductionQueryEvaluator {
    database.pool.foreach { p =>
      p.sizeMin = 1
      p.sizeMax = 1
      p.maxWait = 1.second
    }
  }

  class DevelopmentScheduler(val name: String) extends Scheduler {
    override val jobQueueName = name + "_jobs"
    val schedulerType = new KestrelScheduler {
      val queuePath = "."
    }

    errorLimit = 100
    errorRetryDelay = 1.minute
    errorStrobeInterval = 1.second
    perFlushItemLimit = 100
    jitterRate = 0
  }

  val jobQueues = Map(
    Priority.High.id    -> new DevelopmentScheduler("edges") { threads = 32 },
    Priority.Medium.id  -> new DevelopmentScheduler("copy") { threads = 12; errorRetryDelay = 60.seconds },
    Priority.Low.id     -> new DevelopmentScheduler("edges_slow") { threads = 2 }
  )

  val adminConfig = new AdminConfig {
    val textPort = 9991
    val httpPort = 9990
  }

  logging = new LogConfigString("""
log {
  filename = "development.log"
  level = "info"
  roll = "hourly"
  throttle_period_msec = 60000
  throttle_rate = 10
  truncate_stack_traces = 100

}
  """)
}
