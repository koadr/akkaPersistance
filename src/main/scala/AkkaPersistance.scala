import akka.actor._
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration._
import akka.util.Timeout
import akka.event.LoggingReceive
import akka.pattern.{ ask, pipe }
import com.typesafe.config.ConfigFactory


object AkkaPersistance extends App {

  import Worker._

  val config = ConfigFactory.parseString("""
    akka.loglevel = "DEBUG"
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
  """)

  val system = ActorSystem("AkkaPersistance", config)
  val worker = system.actorOf(Props[Worker], name = "worker")
  val listener = system.actorOf(Props[Listener], name = "listener")
  worker.tell(Start, sender = listener)
  
}

class Listener extends Actor with ActorLogging {
  import Worker._

  context.setReceiveTimeout(20 seconds)

  override def receive = LoggingReceive {
    case Progress(percent) => {
      if (percent > 100) {
        log.info("All done!!!")
        context.system.shutdown()
      } else
        log.info(s"Worker ${sender} has made ${percent} % progress thus far.")
    }

    case ReceiveTimeout =>
      log.error("Service Unavailable")
      context.system.shutdown()
  }
}

class Worker extends Actor {
  import Worker._
  import CounterService._

  implicit val askTimeout = Timeout(5 seconds)

  // Stop the CounterService child if it throws ServiceUnavailable
  override val supervisorStrategy = OneForOneStrategy() {
    case _: CounterService.ServiceUnavailable ⇒ Stop
  }

  val counterService = context.actorOf(Props[CounterService], name = "counterService")

  var progressListener : Option[ActorRef] = None

  val totalCount = 51
  import context.dispatcher

  override def receive = LoggingReceive {
    case Start if progressListener.isEmpty =>
      progressListener = Some(sender)
      context.system.scheduler.schedule(Duration.Zero,1 second, self, Do)
    case Do =>
      counterService ! Increment(1)
      counterService ! Increment(1)
      counterService ! Increment(1)

      // Send progress back to original sender
      counterService ? GetCurrentCount map {
        case CurrentCount(_,count) => Progress( ( 100 * count ) / totalCount)
      } pipeTo(progressListener.get)

  }
}

object Worker {
  case object Do
  case object Start
  case class Progress(percent : Double)
}

class CounterService extends Actor {
  import CounterService._
  import Counter._
  import Storage._

  // Restart the storage child when StorageException is thrown.
  // After 3 restarts within 5 seconds it will be stopped.
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3,
    withinTimeRange = 5 seconds) {
    case _: Storage.StorageException ⇒ Restart
  }

  val key = self.path.name
  var storage: Option[ActorRef] = None
  var counter: Option[ActorRef] = None
  var backlog = IndexedSeq.empty[(ActorRef, Any)]
  val MaxBacklog = 10000

  import context.dispatcher // Use this Actors' Dispatcher as ExecutionContext

  override def preStart() {
    initStorage()
  }


  def initStorage() {
    storage = Some(context.watch(context.actorOf(Props[Storage], name = "storage")))
    // Tell the counter, if any, to use the new storage
    counter foreach { _ ! UseStorage(storage) }
    // We need the initial value to be able to operate
    storage.get ! Get(key)
  }

  def receive = LoggingReceive {

    case Entry(k, v) if k == key && counter == None ⇒
      // Reply from Storage of the initial value, now we can create the Counter
      val c = context.actorOf(Props(classOf[Counter], key, v))
      counter = Some(c)
      // Tell the counter to use current storage
      c ! UseStorage(storage)
      // and send the buffered backlog to the counter
      for ((replyTo, msg) ← backlog) c.tell(msg, sender = replyTo)
      backlog = IndexedSeq.empty

    case msg @ Increment(n)    ⇒ forwardOrPlaceInBacklog(msg)

    case msg @ GetCurrentCount ⇒ forwardOrPlaceInBacklog(msg)

    case Terminated(actorRef) if Some(actorRef) == storage ⇒
      // After 3 restarts the storage child is stopped.
      // We receive Terminated because we watch the child, see initStorage.
      storage = None
      // Tell the counter that there is no storage for the moment
      counter foreach { _ ! UseStorage(None) }
      // Try to re-establish storage after while
      context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)

    case Reconnect ⇒
      // Re-establish storage after the scheduled delay
      initStorage()
  }

  def forwardOrPlaceInBacklog(msg: Any) {
    // We need the initial value from storage before we can start delegate to
    // the counter. Before that we place the messages in a backlog, to be sent
    // to the counter when it is initialized.
    counter match {
      case Some(c) ⇒ c forward msg
      case None ⇒
        if (backlog.size >= MaxBacklog)
          throw new ServiceUnavailable(
            "CounterService not available, lack of initial value")
        backlog :+= (sender -> msg)
    }
  }

}

object CounterService {
  case class Increment(n : Int)
  case object GetCurrentCount
  case class CurrentCount(key: String, count: Long)
  class ServiceUnavailable(msg: String) extends RuntimeException(msg)

  private case object Reconnect
}

object Counter {
  case class UseStorage(storage: Option[ActorRef])
}

class Counter(key: String, initialValue: Long) extends Actor {
  import Counter._
  import CounterService._
  import Storage._

  var count = initialValue
  var storage: Option[ActorRef] = None

  def receive = LoggingReceive {
    case UseStorage(s) ⇒
      storage = s
      storeCount()

    case Increment(n) ⇒
      count += n
      storeCount()

    case GetCurrentCount ⇒
      sender ! CurrentCount(key, count)

  }

  def storeCount() {
    // Delegate dangerous work, to protect our valuable state.
    // We can continue without storage.
    storage foreach { _ ! Store(Entry(key, count)) }
  }
}


object Storage {
  case class Store(entry: Entry)
  case class Get(key: String)
  case class Entry(key: String, value: Long)
  class StorageException(msg: String) extends RuntimeException(msg)
}



class Storage extends Actor {
  import Storage._

  val db = DummyDB

  def receive = LoggingReceive {
    case Store(Entry(key, count)) ⇒ db.save(key, count)
    case Get(key)                 ⇒ sender ! Entry(key, db.load(key).getOrElse(0L))
  }
}

object DummyDB {
  import Storage.StorageException
  private var db = Map[String, Long]()

  @throws(classOf[StorageException])
  def save(key: String, value: Long): Unit = synchronized {
    if (11 <= value && value <= 14)
      throw new StorageException("Simulated store failure " + value)
    db += (key -> value)
  }

  @throws(classOf[StorageException])
  def load(key: String): Option[Long] = synchronized {
    db.get(key)
  }
}


