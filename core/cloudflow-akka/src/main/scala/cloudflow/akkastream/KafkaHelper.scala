package cloudflow.akkastream

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.kafka.{ ConsumerSettings, ProducerSettings }
import cloudflow.blueprint.RunnerConfigUtils
import org.apache.kafka.clients.consumer.{ ConsumerConfig, ConsumerRecord, CooperativeStickyAssignor }
import org.apache.kafka.clients.producer.ProducerRecord

import java.nio.charset.StandardCharsets
import cloudflow.streamlets._

import scala.util.{ Failure, Success }

/**
 * Helper function to encapsulate common logic
 */
object KafkaHelper {
  import org.apache.kafka.common.serialization._

  @InternalApi
  trait ConsumerHelper {
    self: {
      def findTopicForPort(port: StreamletPort): Topic
      def groupId[T](inlet: CodecInlet[T], topic: Topic): String
      def runtimeBootstrapServers(topic: Topic): String
    } =>

    protected def createConsumerSettings[T](
        inlet: CodecInlet[T],
        offsetReset: String,
        stickPartitionAssignment: Boolean = false)(
        implicit system: ActorSystem): (Topic, ConsumerSettings[Array[Byte], Array[Byte]]) = {
      val topic = findTopicForPort(inlet)
      val cs = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
        .withBootstrapServers(runtimeBootstrapServers(topic))
        .withGroupId(groupId(inlet, topic))
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset)
        .withProperties(topic.kafkaConsumerProperties)

      (topic, if (stickPartitionAssignment) {
        val pod_id = RunnerConfigUtils.getPodMetadata("/mnt/downward-api-volume")._3
        cs.withProperty(
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            classOf[CooperativeStickyAssignor].getName())
          .withGroupInstanceId(s"${groupId(inlet, topic)}_${pod_id}")
      } else cs)
    }

    protected def decode[T](inlet: CodecInlet[T], record: ConsumerRecord[Array[Byte], Array[Byte]]): Option[T] =
      inlet.codec.decode(record.value) match {
        case Success(value) => Some(value)
        case Failure(t)     => inlet.errorHandler(record.value, t)
      }
  }

  @InternalApi
  trait ProducerHelper {
    self: {
      def findTopicForPort(port: StreamletPort): Topic
    } =>

    protected def keyBytes(key: String) = if (key != null) key.getBytes(StandardCharsets.UTF_8) else null

    protected def encode[T](outlet: CodecOutlet[T], value: T): ProducerRecord[Array[Byte], Array[Byte]] = {
      val topic = findTopicForPort(outlet)
      val key = outlet.partitioner(value)
      val bytesKey = keyBytes(key)
      val bytesValue = outlet.codec.encode(value)
      new ProducerRecord(topic.name, bytesKey, bytesValue)
    }

    def createProducerSettings(topic: Topic, bootstrapServers: String)(
        implicit system: ActorSystem): ProducerSettings[Array[Byte], Array[Byte]] =
      ProducerSettings(system, new ByteArraySerializer, new ByteArraySerializer)
        .withBootstrapServers(bootstrapServers)
        .withProperties(topic.kafkaProducerProperties)
  }
}
