package ldbc.snb.datagen.transformation.transform

import ldbc.snb.datagen.sql._
import ldbc.snb.datagen.transformation.model.{Batched, BatchedEntity, EntityType, Graph, Mode}
import ldbc.snb.datagen.syntax._
import ldbc.snb.datagen.transformation.model.Mode.BI
import ldbc.snb.datagen.util.Logging
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions._

case class RawToBiTransform(mode: BI, simulationStart: Long, simulationEnd: Long) extends Transform[Mode.Raw.type, Mode.BI] with Logging {
  log.debug(s"BI Transformation parameters: $mode")

  val bulkLoadThreshold = Interactive.calculateBulkLoadThreshold(mode.bulkloadPortion, simulationStart, simulationEnd)

  def batchPeriodFormat(batchPeriod: String) = batchPeriod match {
    case "year" => "yyyy"
    case "month" => "yyyy-MM"
    case "day" => "yyyy-MM-dd"
    case "hour" => "yyyy-MM-dd'T'hh"
    case "minute" => "yyyy-MM-dd'T'hh:mm"
    case _ => "yyyy-MM-dd'T'HH:mm:ss.SSS+00:00"
  }

  override def transform(input: In): Out = {
    val batch_id = (col: Column) =>
      date_format(date_trunc(mode.batchPeriod, col), batchPeriodFormat(mode.batchPeriod))

    def inBatch(col: Column, batchStart: Long, batchEnd: Long) =
      col >= to_timestamp(lit(batchStart / 1000)) &&
        col < to_timestamp(lit(batchEnd / 1000))

    val batched = (df: DataFrame) => df
      .select(
        df.columns.map(qcol) ++ Seq(
          batch_id($"creationDate").as("insert_batch_id"),
          batch_id($"deletionDate").as("delete_batch_id")
        ): _*)
      .filter($"insert_batch_id" =!= $"delete_batch_id")

    val insertBatchPart = (tpe: EntityType, df: DataFrame, batchStart: Long, batchEnd: Long) => {
      df
        .filter(inBatch($"creationDate", batchStart, batchEnd))
        .pipe(batched)
        .select(
          Seq($"insert_batch_id".as("batch_id")) ++ Interactive.columns(tpe, df.columns).map(qcol): _*
        )
        .repartitionByRange($"batch_id")
        .sortWithinPartitions($"creationDate")
    }

    val deleteBatchPart = (tpe: EntityType, df: DataFrame, batchStart: Long, batchEnd: Long) => {
      val idColumns = tpe.primaryKey.map(qcol)
      df
        .filter(inBatch($"deletionDate", batchStart, batchEnd))
        .pipe(batched)
        .select(Seq($"delete_batch_id".as("batch_id"), $"deletionDate") ++ idColumns: _*)
        .repartitionByRange($"batch_id")
        .sortWithinPartitions($"deletionDate")
    }

    val entities = input.entities.map {
      case (tpe, v) if tpe.isStatic => tpe -> BatchedEntity(v, None, None)
      case (tpe, v) =>
        tpe -> BatchedEntity(
          Interactive.snapshotPart(tpe, v, bulkLoadThreshold, filterDeletion = false),
          Some(Batched(insertBatchPart(tpe, v, bulkLoadThreshold, simulationEnd), Seq("batch_id"))),
          Some(Batched(deleteBatchPart(tpe, v, bulkLoadThreshold, simulationEnd), Seq("batch_id")))
      )
    }
    Graph[Mode.BI, DataFrame](isAttrExploded = input.isAttrExploded, isEdgesExploded = input.isEdgesExploded, mode, entities)
  }
}
