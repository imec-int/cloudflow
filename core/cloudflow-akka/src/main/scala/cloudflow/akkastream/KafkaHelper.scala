package cloudflow.akkastream

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.kafka.{ ConsumerSettings, ProducerSettings }
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, ByteArraySerializer }

import java.nio.charset.StandardCharsets
import cloudflow.streamlets._

/**
 * Helper function to encapsulate common logic
 */
object KafkaHelper {

  @InternalApi
  trait ConsumerHelper {
    self: {
      def findTopicForPort(port: StreamletPort): Topic
      def groupId[T](inlet: CodecInlet[T], topic: Topic): String
      def runtimeBootstrapServers(topic: Topic): String
    } =>

    protected def makeConsumerSettings[T](inlet: CodecInlet[T], offsetReset: String)(
        implicit system: ActorSystem): (Topic, ConsumerSettings[Array[Byte], Array[Byte]]) = {
      val topic = findTopicForPort(inlet)

      (
        topic,
        ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
          .withBootstrapServers(runtimeBootstrapServers(topic))
          .withGroupId(groupId(inlet, topic))
          .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset)
          .withProperties(topic.kafkaConsumerProperties))
    }
  }

  @InternalApi
  trait ProducerHelper {

    protected def keyBytes(key: String) = if (key != null) key.getBytes(StandardCharsets.UTF_8) else null

    protected def producerRecord[T](
        outlet: CodecOutlet[T],
        topic: Topic,
        value: T): ProducerRecord[Array[Byte], Array[Byte]] = {
      val key = outlet.partitioner(value)
      val bytesKey = keyBytes(key)
      val bytesValue = outlet.codec.encode(value)
      new ProducerRecord(topic.name, bytesKey, bytesValue)
    }

    def makeProducerSettings(topic: Topic, bootstrapServers: String)(
        implicit system: ActorSystem): ProducerSettings[Array[Byte], Array[Byte]] =
      ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
        .withBootstrapServers(bootstrapServers)
        .withProperties(topic.kafkaProducerProperties)

  }
}
