package com.m2mci.mqttKafkaBridge;

import java.util.Properties;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.kohsuke.args4j.CmdLineException;

object Bridge {

  def main(args: Array[String]) {
    var parser: CommandLineParser = null
    try {
      parser = new CommandLineParser()
      parser.parse(args)
      val bridge = new Bridge()
      bridge.connect(parser.getServerURI, parser.getClientId, parser.getZkConnect)
      bridge.subscribe(parser.getMqttTopicFilters)
    } catch {
      case e: MqttException => e.printStackTrace(System.err)
      case e: CmdLineException =>

        {
        System.err.println(e.getMessage)
        parser.printUsage(System.err)
      }
    }
  }
}

class Bridge extends MqttCallback {
  private var logger: Logger = Logger.getLogger(this.getClass.getName)

  private var mqtt: MqttAsyncClient = _

  private var kafkaProducer: Producer[String, String] = _

  private def connect(serverURI: String, clientId: String, zkConnect: String) {
    mqtt = new MqttAsyncClient(serverURI, clientId)
    mqtt.setCallback(this)
    val token = mqtt.connect()
    val props = new Properties()
    props.put("zk.connect", zkConnect)
    props.put("serializer.class", "kafka.serializer.DefaultEncoder")
    val config = new ProducerConfig(props)
    kafkaProducer = new Producer[String, String](config)
    token.waitForCompletion()
    logger.info("Connected to MQTT and Kafka")
  }

  private def reconnect() {
    val token = mqtt.connect()
    token.waitForCompletion()

  }

  private def subscribe(mqttTopicFilters: Array[String]) {
    val qos = Array.ofDim[Int](mqttTopicFilters.length)
    for (i <- 0 until qos.length) {
      qos(i) = 0

    }
    mqtt.subscribe(mqttTopicFilters, qos)

  }

  override def connectionLost(cause: Throwable) {
    logger.warn("Lost connection to MQTT server", cause)
    while (true) {
      try {
        logger.info("Attempting to reconnect to MQTT server")
        reconnect()
        logger.info("Reconnected to MQTT server, resuming")
        return

      } catch {
        case e: MqttException => logger.warn("Reconnect failed, retrying in 10 seconds", e)
      }
      try {
        Thread.sleep(10000)
      } catch {
        case e: InterruptedException =>
      }
    }
  }

  override def deliveryComplete(token: IMqttDeliveryToken) {
  }

  override def messageArrived(topic: String, message: MqttMessage) {
    val payload = message.getPayload
    val data = new ProducerData[String, Message](topic, new Message(payload))
    kafkaProducer.send(data)
  }
}