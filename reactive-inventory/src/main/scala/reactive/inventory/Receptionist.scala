package reactive.inventory

import akka.actor.{Props, ActorLogging, Actor}
import reactive.inventory.StatsDSender.{IncrementCounter, SendTimer}
import reactive.inventory.InventoryManager.InventoryResponse

object Receptionist {
  trait Request

  case class GetRequest(sku: String, completer: InventoryResponse => Unit) extends Request
  case class PutRequest(sku: String, quantity: Int, completer: InventoryResponse => Unit) extends Request
}

class Receptionist extends Actor
  with ActorLogging
  with Router {

  import InventoryManager._
  import Receptionist._

  //Actor to send metrics
  val statsDSender = context.actorOf(Props[StatsDSender])

  //Initialize state of actor's message handler
  //We store a map of ids to outstanding requests (along with start time for metric)
  //We also store the next sequential id
  def receive = handleRequests(Map[Int, (InventoryResponse => Unit, Long)](), 0)

  def handleRequests(requests: Map[Int, (InventoryResponse => Unit, Long)], nextKey: Int): Receive = {
    case GetRequest (sku, completer) =>
      //Send the get request to the appropriate actor
      //The way we do this is by looking up the name of the actor.
      //In this case we have named every actor after its sku so we just append
      //the sku to the base path and voila
      context.actorSelection("/user/" + sku) ! GetInventory(nextKey)
      //Add request to map and increment id
      //This is done essentially via tail recursion
      context.become(handleRequests(requests + (nextKey -> (completer, System.currentTimeMillis)), nextKey + 1))
    case PutRequest (sku, quantity, completer) =>
      //Ditto above
      context.actorSelection("/user/" + sku) ! UpdateInventory(nextKey, quantity)
      context.become(handleRequests(requests + (nextKey -> (completer, System.currentTimeMillis)), nextKey + 1))
    case InventoryResponse(id, action, sku, success, quantity, message) =>
      requests.get(id) match {
        case Some((completer, startTime)) =>
          //Send response to appropriate request
          completer(InventoryResponse(id, action, sku, success, quantity, message))
          //Send message to actor to record metrics
          statsDSender ! SendTimer(s"reactive.$action.duration", System.currentTimeMillis - startTime)
          statsDSender ! IncrementCounter(s"reactive.$action.count")
          //Remove request from map, again via tail recursion
          context.become(handleRequests(requests - id, nextKey))
        case _ =>
      }
  }
}