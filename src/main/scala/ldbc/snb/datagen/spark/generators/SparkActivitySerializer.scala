package ldbc.snb.datagen.spark.generators

import ldbc.snb.datagen.entities.dynamic.person.Person
import ldbc.snb.datagen.generator.generators.{GenActivity, PersonActivityGenerator}
import ldbc.snb.datagen.serializer.{DummyDeleteEventSerializer, DummyInsertEventSerializer, PersonActivityExporter}
import ldbc.snb.datagen.spark.util.SerializableConfiguration
import ldbc.snb.datagen.syntax._
import ldbc.snb.datagen.util.LdbcConfiguration
import ldbc.snb.datagen.{DatagenContext, DatagenParams}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

import java.nio.charset.StandardCharsets
import java.util
import java.util.function.Consumer
import scala.collection.JavaConverters._

object SparkActivitySerializer {

  def apply(persons: RDD[Person], ranker: SparkRanker, conf: LdbcConfiguration, partitions: Option[Int] = None)(implicit spark: SparkSession) = {

    val blockSize = DatagenParams.blockSize
    val blocks = ranker(persons)
      .map { case (k, v) => (k / blockSize, v) }
      .groupByKey()
      .withFoldLeft(partitions, (rdd: RDD[(Long, Iterable[Person])], p: Int) => rdd.coalesce(p))

    val serializableHadoopConf = new SerializableConfiguration(spark.sparkContext.hadoopConfiguration)

    blocks.foreachPartition(groups => {
      DatagenContext.initialize(conf)
      val partitionId = TaskContext.getPartitionId()
      val hadoopConf = serializableHadoopConf.value
      val buildDir = conf.getBuildDir

      val fs = FileSystem.get(hadoopConf)
      fs.mkdirs(new Path(buildDir))

      val dynamicActivitySerializer = conf.getDynamicActivitySerializer

      dynamicActivitySerializer.initialize(hadoopConf, conf.getSocialNetworkDir, partitionId, conf.isCompressed, conf.insertTrailingSeparator())

      val insertEventSerializer = new DummyInsertEventSerializer
      val deleteEventSerializer = new DummyDeleteEventSerializer

      val generator = new PersonActivityGenerator
      val exporter = new PersonActivityExporter(dynamicActivitySerializer, insertEventSerializer, deleteEventSerializer, generator.getFactorTable)
      val friends = fs.create(new Path(buildDir + "/" + "m0friendList" + partitionId + ".csv"))
      val personFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.PERSON_FACTORS_FILE))
      val postsPerCountryFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.POSTS_PER_COUNTRY_FACTOR_FILE))
      val tagClassFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.TAGCLASS_FACTOR_FILE))
      val tagFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.TAG_FACTOR_FILE))
      val firstNameFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.FIRSTNAME_FACTOR_FILE))
      val miscFactors = fs.create(new Path(buildDir + "/" + "m" + partitionId + DatagenParams.MISC_FACTOR_FILE))

      try {
        for {(blockId, persons) <- groups} {
          val clonedPersons = new util.ArrayList[Person]
          for (p <- persons) {
            clonedPersons.add(new Person(p))

            val strbuf = new StringBuilder
            for (k <- p.getKnows.iterator().asScala) {
              strbuf.append(p.getAccountId)
              strbuf.append("|")
              strbuf.append(k.to.getAccountId)
              strbuf.append("\n")
            }
            friends.write(strbuf.toString().getBytes(StandardCharsets.UTF_8))
          }

          val activities = generator.generateActivityForBlock(blockId.toInt, clonedPersons)

          activities.forEach(new Consumer[GenActivity] {
            override def accept(t: GenActivity): Unit = exporter.export(t)
          })

          generator.writePersonFactors(personFactors)
        }
        generator.writeActivityFactors(postsPerCountryFactors, tagClassFactors, tagFactors, firstNameFactors, miscFactors)
      } finally {
        exporter.close()
        postsPerCountryFactors.close()
        tagClassFactors.close()
        tagFactors.close()
        firstNameFactors.close()
        miscFactors.close()
        personFactors.close()
        friends.close()
      }
    })
  }
}
