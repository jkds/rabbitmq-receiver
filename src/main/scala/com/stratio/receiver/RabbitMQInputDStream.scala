/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.receiver

import java.util

import scala.util._

import com.rabbitmq.client._
import org.apache.spark.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.receiver.Receiver

private[receiver]
class RabbitMQReceiver(queueName: String,
                       host: String,
                       port: Int,
                       username : Option[String],
                       password : Option[String],
                       exchangeName: Option[String],
                       routingKeys: Option[Seq[String]],
                       storageLevel: StorageLevel)
  extends Receiver[String](storageLevel) with Logging {

  val DirectExchangeType: String = "direct"

  def onStart() {
    implicit val akkaSystem = akka.actor.ActorSystem()
    getConnectionAndChannel match {
      case Success((connection: Connection, channel: Channel)) => receive(connection, channel)
      case Failure(f) => log.error("Could not connect"); restart("Could not connect", f)
    }
  }

  def onStop() {
    // There is nothing much to do as the thread calling receive()
    // is designed to stop by itself isStopped() returns false
  }

  /** Create a socket connection and receive data until receiver is stopped */
  private def receive(connection: Connection, channel: Channel) {

    val qName = !routingKeys.isEmpty match {
      case true => {
        channel.exchangeDeclare(exchangeName.get, DirectExchangeType)
        val qName = channel.queueDeclare().getQueue()
        routingKeys.map { keys =>
          keys.foreach(channel.queueBind(qName, exchangeName.get, _))
        }
        qName
      }
      case false => {
        channel.queueDeclare(queueName, true, false, false, new util.HashMap(0))
        queueName
      }
    }

    log.info("RabbitMQ Input waiting for messages")
    val consumer: QueueingConsumer = new QueueingConsumer(channel)
    channel.basicConsume(qName, true, consumer)

    while (!isStopped) {
      val delivery: QueueingConsumer.Delivery = consumer.nextDelivery
      store(new String(delivery.getBody))
    }

    log.info("it has been stopped")
    channel.close
    connection.close
    restart("Trying to connect again")
  }

  private def getConnectionAndChannel: Try[(Connection, Channel)] = {
    for {
      connection: Connection <- Try(getConnectionFactory.newConnection())
      channel: Channel <- Try(connection.createChannel)
    } yield {
      (connection, channel)
    }
  }

  private def getConnectionFactory: ConnectionFactory = {
    val factory: ConnectionFactory = new ConnectionFactory
    factory.setHost(host)
    factory.setPort(port)
    username.map(factory.setUsername(_))
    password.map(factory.setPassword(_))
    factory
  }
}
