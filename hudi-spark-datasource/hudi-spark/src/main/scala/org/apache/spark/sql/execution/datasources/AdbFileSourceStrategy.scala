package org.apache.spark.sql.execution.datasources

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.catalyst.expressions
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, AttributeSet, EmptyRow, Expression, ExpressionSet, Literal, NamedExpression, PredicateHelper, SubqueryExpression}
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.datasources.parquet.{AdbParquetFileFormat, ParquetFileFormat}
import org.apache.spark.sql.execution.{AdbConfig, AdbFileSourceScanExec, FileSourceScanExec, SparkPlan}
import org.apache.spark.sql.{Strategy, execution}
import org.apache.spark.util.collection.BitSet

/**
 * HoodieFileSourceStrategy use to intercept [[FileSourceStrategy]]
 */
object AdbFileSourceStrategy extends Strategy with PredicateHelper with Logging {

  // should prune buckets iff num buckets is greater than 1 and there is only one bucket column
  private def shouldPruneBuckets(bucketSpec: Option[BucketSpec]): Boolean = {
    bucketSpec match {
      case Some(spec) => spec.bucketColumnNames.length == 1 && spec.numBuckets > 1
      case None => false
    }
  }

  private def getExpressionBuckets(
                                    expr: Expression,
                                    bucketColumnName: String,
                                    numBuckets: Int): BitSet = {

    def getBucketNumber(attr: Attribute, v: Any): Int = {
      BucketingUtils.getBucketIdFromValue(attr, numBuckets, v)
    }

    def getBucketSetFromIterable(attr: Attribute, iter: Iterable[Any]): BitSet = {
      val matchedBuckets = new BitSet(numBuckets)
      iter
        .map(v => getBucketNumber(attr, v))
        .foreach(bucketNum => matchedBuckets.set(bucketNum))
      matchedBuckets
    }

    def getBucketSetFromValue(attr: Attribute, v: Any): BitSet = {
      val matchedBuckets = new BitSet(numBuckets)
      matchedBuckets.set(getBucketNumber(attr, v))
      matchedBuckets
    }

    expr match {
      case expressions.Equality(a: Attribute, Literal(v, _)) if a.name == bucketColumnName =>
        getBucketSetFromValue(a, v)
      case expressions.In(a: Attribute, list)
        if list.forall(_.isInstanceOf[Literal]) && a.name == bucketColumnName =>
        getBucketSetFromIterable(a, list.map(e => e.eval(EmptyRow)))
      case expressions.InSet(a: Attribute, hset)
        if hset.forall(_.isInstanceOf[Literal]) && a.name == bucketColumnName =>
        getBucketSetFromIterable(a, hset.map(e => expressions.Literal(e).eval(EmptyRow)))
      case expressions.IsNull(a: Attribute) if a.name == bucketColumnName =>
        getBucketSetFromValue(a, null)
      case expressions.And(left, right) =>
        getExpressionBuckets(left, bucketColumnName, numBuckets) &
          getExpressionBuckets(right, bucketColumnName, numBuckets)
      case expressions.Or(left, right) =>
        getExpressionBuckets(left, bucketColumnName, numBuckets) |
          getExpressionBuckets(right, bucketColumnName, numBuckets)
      case _ =>
        val matchedBuckets = new BitSet(numBuckets)
        matchedBuckets.setUntil(numBuckets)
        matchedBuckets
    }
  }

  private def genBucketSet(
                            normalizedFilters: Seq[Expression],
                            bucketSpec: BucketSpec): Option[BitSet] = {
    val bucketSet = if (normalizedFilters.isEmpty) {
      None
    } else {
      val bucketColumnName = bucketSpec.bucketColumnNames.head
      val numBuckets = bucketSpec.numBuckets

      val normalizedFiltersAndExpr = normalizedFilters
        .reduce(expressions.And)
      val matchedBuckets = getExpressionBuckets(normalizedFiltersAndExpr, bucketColumnName,
        numBuckets)

      val numBucketsSelected = matchedBuckets.cardinality()

      logInfo {
        s"Pruned ${numBuckets - numBucketsSelected} out of $numBuckets buckets."
      }

      // None means all the buckets need to be scanned
      if (numBucketsSelected == numBuckets) {
        None
      } else {
        Some(matchedBuckets)
      }
    }

    bucketSet
  }

  private def eliminatePredicates(condition: Seq[Expression],
                                  toEliminate: Seq[Expression]): Option[Seq[Expression]] = {
    val newCondition = if (toEliminate.isEmpty) {
      condition
    } else {
      condition.filterNot(toEliminate.contains(_))
    }

    Option(newCondition)
  }

  def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case PhysicalOperation(projects, filters,
    l@LogicalRelation(fsRelation: HadoopFsRelation, _, table, _)) =>
      // Filters on this relation fall into four categories based on where we can use them to avoid
      // reading unneeded data:
      //  - partition keys only - used to prune directories to read
      //  - bucket keys only - optionally used to prune files to read
      //  - keys stored in the data only - optionally used to skip groups of data in files
      //  - filters that need to be evaluated again after the scan
      val filterSet = ExpressionSet(filters)

      // The attribute name of predicate could be different than the one in schema in case of
      // case insensitive, we should change them to match the one in schema, so we do not need to
      // worry about case sensitivity anymore.
      val normalizedFilters = filters.map { e =>
        e transform {
          case a: AttributeReference =>
            a.withName(l.output.find(_.semanticEquals(a)).get.name)
        }
      }

      val partitionColumns =
        l.resolve(
          fsRelation.partitionSchema, fsRelation.sparkSession.sessionState.analyzer.resolver)
      val partitionSet = AttributeSet(partitionColumns)
      val partitionKeyFilters =
        ExpressionSet(normalizedFilters
          .filterNot(SubqueryExpression.hasSubquery(_))
          .filter(_.references.subsetOf(partitionSet)))

      logInfo(s"Pruning directories with: ${partitionKeyFilters.mkString(",")}")

      val bucketSpec: Option[BucketSpec] = fsRelation.bucketSpec
      val bucketSet = if (shouldPruneBuckets(bucketSpec)) {
        genBucketSet(normalizedFilters, bucketSpec.get)
      } else {
        None
      }

      val dataColumns =
        l.resolve(fsRelation.dataSchema, fsRelation.sparkSession.sessionState.analyzer.resolver)

      // Partition keys are not available in the statistics of the files.
      val dataFilters = normalizedFilters.filter(_.references.intersect(partitionSet).isEmpty)

      // Predicates with both partition keys and attributes need to be evaluated after the scan.
      val afterScanFilters = filterSet -- partitionKeyFilters.filter(_.references.nonEmpty)
      logInfo(s"Post-Scan Filters: ${afterScanFilters.mkString(",")}")

      val filterAttributes = AttributeSet(afterScanFilters)
      val requiredExpressions: Seq[NamedExpression] = filterAttributes.toSeq ++ projects
      val requiredAttributes = AttributeSet(requiredExpressions)

      val readDataColumns =
        dataColumns
          .filter(requiredAttributes.contains)
          .filterNot(partitionColumns.contains)
      val outputSchema = readDataColumns.toStructType
      logInfo(s"Output Data Schema: ${outputSchema.simpleString(5)}")

      val outputAttributes = readDataColumns ++ partitionColumns

      // Transfer some conditions from where to prewhere if enabled and viable
      // Placed here for afterScanFilter can exclude prewhere filters
      val prewhereEnabled = fsRelation.sparkSession.conf.get(
        AdbConfig.ADB_WHERE_OPTIMIZATION_ENABLED)
      val prewhereFilters = if (prewhereEnabled) {
        WhereOptimizer.optimize(filters, readDataColumns)
      } else {
        None
      }

      logInfo(s"Prewhere Filters: ${prewhereFilters.map(_.mkString(",")).getOrElse("")}")

      val sparkPlan = fsRelation.fileFormat match {
        // New plan with prewhere predicates injected
        case _: ParquetFileFormat if prewhereFilters.nonEmpty =>
          // Use new parquet file format to inject optimized file reader
          val optimizedParquetFileFormat = new AdbParquetFileFormat
          val newFsRelation = fsRelation.copy(fileFormat = optimizedParquetFileFormat)(fsRelation.sparkSession)

          val scan =
            AdbFileSourceScanExec(
              newFsRelation,
              outputAttributes,
              outputSchema,
              partitionKeyFilters.toSeq,
              bucketSet,
              prewhereFilters.get,
              dataFilters,
              table.map(_.identifier))

          // Prewhere conditions do not need to be executed again at the computing layer
          val newAfterScanFilters = eliminatePredicates(afterScanFilters.toSeq, prewhereFilters.get)
          logInfo(s"After-Scan Filters: ${newAfterScanFilters.map(_.mkString(",")).getOrElse("")}")

          val afterScanFilter = newAfterScanFilters.get.reduceOption(expressions.And)
          val withFilter = afterScanFilter.map(execution.FilterExec(_, scan)).getOrElse(scan)
          val withProjections = if (projects == withFilter.output) {
            withFilter
          } else {
            execution.ProjectExec(projects, withFilter)
          }


          withProjections :: Nil

        // Original way
        case _: FileFormat =>
          val scan =
            FileSourceScanExec(
              fsRelation,
              outputAttributes,
              outputSchema,
              partitionKeyFilters.toSeq,
              bucketSet,
              dataFilters,
              table.map(_.identifier))

          val afterScanFilter = afterScanFilters.toSeq.reduceOption(expressions.And)
          val withFilter = afterScanFilter.map(execution.FilterExec(_, scan)).getOrElse(scan)
          val withProjections = if (projects == withFilter.output) {
            withFilter
          } else {
            execution.ProjectExec(projects, withFilter)
          }

          withProjections :: Nil
      }

      sparkPlan

    case _ => Nil
  }
}
