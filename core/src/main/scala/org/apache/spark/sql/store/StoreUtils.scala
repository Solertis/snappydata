/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.store

import java.util.regex.Pattern

import scala.collection.JavaConverters._
import scala.collection.generic.Growable
import scala.collection.mutable

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
import com.gemstone.gemfire.internal.cache.{CacheDistributionAdvisee, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc

import org.apache.spark.Partition
import org.apache.spark.sql.collection.{MultiBucketExecutorPartition, ToolsCallbackInit, Utils}
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.sources.JdbcExtendedUtils
import org.apache.spark.sql.types._
import org.apache.spark.sql.{AnalysisException, BlockAndExecutorId, SQLContext, SnappyContext, SnappySession}


object StoreUtils {

  val PARTITION_BY = ExternalStoreUtils.PARTITION_BY
  val REPLICATE = ExternalStoreUtils.REPLICATE
  val BUCKETS = ExternalStoreUtils.BUCKETS
  val PARTITIONER = "PARTITIONER"
  val COLOCATE_WITH = "COLOCATE_WITH"
  val REDUNDANCY = "REDUNDANCY"
  val RECOVERYDELAY = "RECOVERYDELAY"
  val MAXPARTSIZE = "MAXPARTSIZE"
  val EVICTION_BY = "EVICTION_BY"
  val PERSISTENCE = "PERSISTENCE"
  val PERSISTENT = "PERSISTENT"
  val DISKSTORE = "DISKSTORE"
  val SERVER_GROUPS = "SERVER_GROUPS"
  val EXPIRE = "EXPIRE"
  val OVERFLOW = "OVERFLOW"

  val GEM_PARTITION_BY = "PARTITION BY"
  val GEM_BUCKETS = "BUCKETS"
  val GEM_PARTITIONER = "PARTITIONER"
  val GEM_COLOCATE_WITH = "COLOCATE WITH"
  val GEM_REDUNDANCY = "REDUNDANCY"
  val GEM_REPLICATE = "REPLICATE"
  val GEM_RECOVERYDELAY = "RECOVERYDELAY"
  val GEM_MAXPARTSIZE = "MAXPARTSIZE"
  val GEM_EVICTION_BY = "EVICTION BY"
  val GEM_PERSISTENT = "PERSISTENT"
  val GEM_SERVER_GROUPS = "SERVER GROUPS"
  val GEM_EXPIRE = "EXPIRE"
  val GEM_OVERFLOW = "EVICTACTION OVERFLOW"
  val GEM_HEAPPERCENT = "EVICTION BY LRUHEAPPERCENT "
  val PRIMARY_KEY = "PRIMARY KEY"
  val LRUCOUNT = "LRUCOUNT"
  val GEM_INDEXED_TABLE = "INDEXED_TABLE"

  // int values for Spark SQL types for efficient switching avoiding reflection
  val STRING_TYPE = 0
  val INT_TYPE = 1
  val LONG_TYPE = 2
  val BINARY_LONG_TYPE = 3
  val SHORT_TYPE = 4
  val BYTE_TYPE = 5
  val BOOLEAN_TYPE = 6
  val DECIMAL_TYPE = 7
  val DOUBLE_TYPE = 8
  val FLOAT_TYPE = 9
  val DATE_TYPE = 10
  val TIMESTAMP_TYPE = 11
  val BINARY_TYPE = 12
  val ARRAY_TYPE = 13
  val MAP_TYPE = 14
  val STRUCT_TYPE = 15

  val ddlOptions: Seq[String] = Seq(PARTITION_BY, REPLICATE, BUCKETS, PARTITIONER,
    COLOCATE_WITH, REDUNDANCY, RECOVERYDELAY, MAXPARTSIZE, EVICTION_BY,
    PERSISTENCE, PERSISTENT, SERVER_GROUPS, EXPIRE, OVERFLOW,
    GEM_INDEXED_TABLE) ++ ExternalStoreUtils.ddlOptions

  val EMPTY_STRING = ""
  val NONE = "NONE"

  val ROWID_COLUMN_NAME = "SNAPPYDATA_INTERNAL_ROWID"

  val ROWID_COLUMN_FIELD = StructField("SNAPPYDATA_INTERNAL_ROWID", LongType, nullable = false)

  val ROWID_COLUMN_DEFINITION = s"$ROWID_COLUMN_NAME bigint generated always as identity"

  val PRIMARY_KEY_PATTERN: Pattern = Pattern.compile("\\WPRIMARY\\s+KEY\\W",
    Pattern.CASE_INSENSITIVE | Pattern.DOTALL)

  // private property to indicate One-to-one mapping of partitions to buckets
  // which is enabled per-query using `LinkPartitionsToBuckets` rule
  private[sql] val PROPERTY_PARTITION_BUCKET_LINKED = "linkPartitionsToBuckets"

  def lookupName(tableName: String, schema: String): String = {
    val lookupName = {
      if (tableName.indexOf('.') <= 0) {
        schema + '.' + tableName
      } else tableName
    }
    lookupName
  }

  private[sql] def getBucketPreferredLocations(region: PartitionedRegion,
      bucketId: Int, forWrite: Boolean): Seq[String] = {
    if (forWrite) {
      val primary = region.getOrCreateNodeForBucketWrite(bucketId, null).toString
      SnappyContext.getBlockId(primary) match {
        case Some(b) => Seq(Utils.getHostExecutorId(b.blockId))
        case None => Seq.empty
      }
    } else {
      val distMembers = getBucketOwnersForRead(bucketId, region)
      val members = new mutable.ArrayBuffer[String](2)
      distMembers.foreach { m =>
        SnappyContext.getBlockId(m.toString) match {
          case Some(b) => members += Utils.getHostExecutorId(b.blockId)
          case None =>
        }
      }
      members
    }
  }

  private[sql] def getBucketOwnersForRead(bucketId: Int,
      region: PartitionedRegion): mutable.Set[InternalDistributedMember] = {
    val distMembers = region.getRegionAdvisor.getBucketOwners(bucketId).asScala
    if (distMembers.isEmpty) {
      var prefNode = region.getRegionAdvisor.getPreferredInitializedNode(bucketId, true)
      if (prefNode == null) {
        prefNode = region.getOrCreateNodeForInitializedBucketRead(bucketId, true)
      }
      distMembers.add(prefNode)
    }
    distMembers
  }

  private[sql] def getPartitionsPartitionedTable(session: SnappySession,
      region: PartitionedRegion,
      linkBucketsToPartitions: Boolean): Array[Partition] = {

    val callbacks = ToolsCallbackInit.toolsCallback
    if (!linkBucketsToPartitions && callbacks != null) {
      allocateBucketsToPartitions(session, region)
    } else {
      val numPartitions = region.getTotalNumberOfBuckets

      (0 until numPartitions).map { p =>
        val prefNodes = getBucketPreferredLocations(region, p, forWrite = false)
        val buckets = new mutable.ArrayBuffer[Int](1)
        buckets += p
        new MultiBucketExecutorPartition(p, buckets, numPartitions, prefNodes)
      }.toArray[Partition]
    }
  }

  private[sql] def getPartitionsReplicatedTable(session: SnappySession,
      region: CacheDistributionAdvisee): Array[Partition] = {

    val numPartitions = 1
    val partitions = new Array[Partition](numPartitions)

    val regionMembers = if (Utils.isLoner(session.sparkContext)) {
      Set(Misc.getGemFireCache.getDistributedSystem.getDistributedMember)
    } else {
      region.getCacheDistributionAdvisor.adviseInitializedReplicates().asScala
    }
    val prefNodes = regionMembers.collect {
      case m if SnappyContext.containsBlockId(m.toString) =>
        Utils.getHostExecutorId(SnappyContext.getBlockId(m.toString).get.blockId)
    }.toSeq
    partitions(0) = new MultiBucketExecutorPartition(0, null, 0, prefNodes)
    partitions
  }

  private def allocateBucketsToPartitions(session: SnappySession,
      region: PartitionedRegion): Array[Partition] = {

    val numTotalBuckets = region.getTotalNumberOfBuckets
    val serverToBuckets = new mutable.HashMap[InternalDistributedMember,
        (Option[BlockAndExecutorId], mutable.ArrayBuffer[Int])]()
    val adviser = region.getRegionAdvisor
    for (p <- 0 until numTotalBuckets) {
      var prefNode = adviser.getPreferredInitializedNode(p, true)
      if (prefNode == null) {
        prefNode = region.getOrCreateNodeForInitializedBucketRead(p, true)
      }
      // prefer another copy if this one does not have an executor
      val prefBlockId = SnappyContext.getBlockId(prefNode.toString) match {
        case b@Some(_) => b
        case None =>
          prefNode = adviser.getBucketOwners(p).asScala.find(m =>
            SnappyContext.containsBlockId(m.toString)).getOrElse(prefNode)
          SnappyContext.getBlockId(prefNode.toString)
      }
      val buckets = serverToBuckets.get(prefNode) match {
        case Some(b) => b._2
        case None =>
          val buckets = new mutable.ArrayBuffer[Int]()
          serverToBuckets.put(prefNode, prefBlockId -> buckets)
          buckets
      }
      buckets += p
    }
    // marker array to check that all buckets have been allocated
    val allocatedBuckets = new Array[Boolean](numTotalBuckets)
    // group buckets into as many partitions as available cores on each member
    var partitionIndex = -1
    val partitions = serverToBuckets.flatMap { case (m, (blockId, buckets)) =>
      val numBuckets = buckets.length
      val numPartitions = math.max(1, blockId.map(b => math.min(math.min(
        b.numProcessors, b.executorCores), numBuckets)).getOrElse(numBuckets))
      val minPartitions = numBuckets / numPartitions
      val remaining = numBuckets % numPartitions
      var partitionStart = 0
      (0 until numPartitions).map { index =>
        val partitionEnd = partitionStart + (
            if (index < remaining) minPartitions + 1 else minPartitions)
        // find any alternative servers for whole bucket group
        val partBuckets = buckets.slice(partitionStart, partitionEnd)
        val allAlternates = partBuckets.map { bucketId =>
          assert(!allocatedBuckets(bucketId), s"Double allocate for $bucketId")
          allocatedBuckets(bucketId) = true
          // remove self from the bucket owners before intersect;
          // add back at the start before returning the list
          val owners = adviser.getBucketOwners(bucketId)
          owners.remove(m)
          owners.asScala
        }
        // Asif: This check is needed as in my tests found reduce throwing
        // UnsupportedOperationException if the buffer is empty
        val alternates = if (allAlternates.isEmpty) {
          mutable.Set.empty[InternalDistributedMember]
        } else {
          allAlternates.reduce { (set1, set2) =>
            // empty check useful only for set on left which is result
            // of previous intersect
            if (set1.isEmpty) set1
            else set1.intersect(set2)
          }
        }
        partitionStart = partitionEnd
        val preferredLocations = (blockId :: alternates.map(mbr =>
          SnappyContext.getBlockId(mbr.toString)).toList).collect {
          case Some(b) => Utils.getHostExecutorId(b.blockId)
        }
        partitionIndex += 1
        new MultiBucketExecutorPartition(partitionIndex, partBuckets,
          numTotalBuckets, preferredLocations)
      }
    }.toArray[Partition]
    assert(allocatedBuckets.forall(_ == true),
      s"Failed to allocate a bucket (${allocatedBuckets.toSeq})")
    partitions
  }

  def removeCachedObjects(sqlContext: SQLContext, table: String,
      registerDestroy: Boolean = false): Unit = {
    ExternalStoreUtils.removeCachedObjects(sqlContext, table, registerDestroy)
  }

  def appendClause(sb: mutable.StringBuilder,
      getClause: () => String): Unit = {
    val clause = getClause.apply()
    if (!clause.isEmpty) {
      sb.append(s"$clause ")
    }
  }

  val pkDisallowdTypes = Seq(StringType, BinaryType, ArrayType, MapType, StructType)

  def getPrimaryKeyClause(parameters: mutable.Map[String, String],
      schema: StructType, context: SQLContext): (String, Seq[StructField]) = {
    val sb = new StringBuilder()
    val stringPKCols = new mutable.ArrayBuffer[StructField](1)
    sb.append(parameters.get(PARTITION_BY).map(v => {
      val primaryKey = {
        v match {
          case PRIMARY_KEY => ""
          case _ =>
            val normalizedSchema = context.sessionState.catalog
                .asInstanceOf[SnappyStoreHiveCatalog]
                .normalizeSchema(schema)
            val schemaFields = Utils.schemaFields(normalizedSchema)
            val cols = v.split(",") map (_.trim)
            // always use case-insensitive analysis for partitioning columns
            // since table creation can use case-insensitive in creation
            val normalizedCols = cols.map(Utils.toUpperCase)
            val prunedSchema = ExternalStoreUtils.pruneSchema(schemaFields,
              normalizedCols)

            var includeInPK = true
            for (field <- prunedSchema.fields if includeInPK) {
              if (pkDisallowdTypes.contains(field.dataType)) {
                includeInPK = false
              }
              /* (string type handling excluded for now due to possible regression impact)
              else if (field.dataType == StringType) {
                stringPKCols += field
              }
              */
            }
            if (includeInPK) {
              s"$PRIMARY_KEY ($v, $ROWID_COLUMN_NAME)"
            } else {
              s"$PRIMARY_KEY ($ROWID_COLUMN_NAME)"
            }
        }
      }
      primaryKey
    }).getOrElse(s"$PRIMARY_KEY ($ROWID_COLUMN_NAME)"))
    (sb.toString(), stringPKCols)
  }

  def ddlExtensionString(parameters: mutable.Map[String, String],
      isRowTable: Boolean, isShadowTable: Boolean): String = {
    val sb = new StringBuilder()

    if (!isShadowTable) {
      sb.append(parameters.remove(PARTITION_BY).map(v => {
        val (parClause) = {
          v match {
            case PRIMARY_KEY =>
              if (isRowTable) {
                s"sparkhash $PRIMARY_KEY"
              } else {
                throw Utils.analysisException("Column table cannot be " +
                    "partitioned on PRIMARY KEY as no primary key")
              }
            case _ => s"sparkhash COLUMN($v)"
          }
        }
        s"$GEM_PARTITION_BY $parClause "
      }).getOrElse(if (isRowTable) EMPTY_STRING
      else s"$GEM_PARTITION_BY COLUMN ($ROWID_COLUMN_NAME) "))
    } else {
      parameters.remove(PARTITION_BY).foreach {
        case PRIMARY_KEY => throw Utils.analysisException("Column table " +
            "cannot be partitioned on PRIMARY KEY as no primary key")
        case _ =>
      }
    }

    if (!isShadowTable) {
      sb.append(parameters.remove(COLOCATE_WITH).map(
        v => s"$GEM_COLOCATE_WITH ($v) ").getOrElse(EMPTY_STRING))
    }

    parameters.remove(REPLICATE).foreach(v =>
      if (v.toBoolean) sb.append(GEM_REPLICATE).append(' ')
      else if (!parameters.contains(BUCKETS)) {
        sb.append(GEM_BUCKETS).append(' ').append(
          ExternalStoreUtils.defaultTableBuckets).append(' ')
      })
    sb.append(parameters.remove(BUCKETS).map(v => s"$GEM_BUCKETS $v ")
        .getOrElse(EMPTY_STRING))
    sb.append(parameters.remove(REDUNDANCY).map(v => s"$GEM_REDUNDANCY $v ")
        .getOrElse(EMPTY_STRING))
    sb.append(parameters.remove(RECOVERYDELAY).map(
      v => s"$GEM_RECOVERYDELAY $v ").getOrElse(EMPTY_STRING))
    sb.append(parameters.remove(MAXPARTSIZE).map(v => s"$GEM_MAXPARTSIZE $v ")
        .getOrElse(EMPTY_STRING))

    // custom partition resolver
    parameters.remove(PARTITIONER).foreach(v =>
      sb.append(GEM_PARTITIONER).append('\'').append(v).append("' "))

    // if OVERFLOW has been provided, then use HEAPPERCENT as the default
    // eviction policy (unless overridden explicitly)
    val hasOverflow = parameters.get(OVERFLOW).map(_.toBoolean)
        .getOrElse(!isRowTable && !parameters.contains(EVICTION_BY))
    val defaultEviction = if (hasOverflow) GEM_HEAPPERCENT else EMPTY_STRING
    var overflowAdded = false
    if (!isShadowTable) {
      sb.append(parameters.remove(EVICTION_BY).map(v =>
        if (v == NONE) {
          EMPTY_STRING
        } else {
          if (hasOverflow) {
            overflowAdded = true
            s"$GEM_EVICTION_BY $v $GEM_OVERFLOW "
          } else {
            s"$GEM_EVICTION_BY $v "
          }
        })
        .getOrElse(defaultEviction))
    } else {
      sb.append(parameters.remove(EVICTION_BY).map(v => {
        if (v.contains(LRUCOUNT)) {
          throw Utils.analysisException(
            "Column table cannot take LRUCOUNT as eviction policy")
        } else if (v == NONE) {
          EMPTY_STRING
        } else {
          if (hasOverflow) {
            overflowAdded = true
            s"$GEM_EVICTION_BY $v $GEM_OVERFLOW "
          } else {
            s"$GEM_EVICTION_BY $v "
          }
        }
      }).getOrElse(defaultEviction))
    }

    if (hasOverflow && !overflowAdded) {
      parameters.remove(OVERFLOW)
      sb.append(s"$GEM_OVERFLOW ")
    }

    // default is sync persistence for all snappydata tables
    var isPersistent = true
    parameters.remove(PERSISTENCE).orElse(parameters.remove(PERSISTENT)).map { v =>
      if (v.equalsIgnoreCase("async") || v.equalsIgnoreCase("asynchronous")) {
        sb.append(s"$GEM_PERSISTENT ASYNCHRONOUS ")
      } else if (v.equalsIgnoreCase("sync") ||
          v.equalsIgnoreCase("synchronous")) {
        sb.append(s"$GEM_PERSISTENT SYNCHRONOUS ")
      } else if (v.equalsIgnoreCase("none")) {
        isPersistent = false
        sb
      } else {
        throw Utils.analysisException(s"Invalid value for option " +
            s"$PERSISTENCE = $v (expected one of: sync, async, none, " +
            s"synchronous, asynchronous)")
      }
    }.getOrElse(sb.append(s"$GEM_PERSISTENT SYNCHRONOUS "))

    parameters.remove(DISKSTORE).foreach { v =>
      if (isPersistent) sb.append(s"'$v' ")
      else throw Utils.analysisException(
        s"Option '$DISKSTORE' requires '$PERSISTENCE' option")
    }
    sb.append(parameters.remove(SERVER_GROUPS)
        .map(v => s"$GEM_SERVER_GROUPS ($v) ")
        .getOrElse(EMPTY_STRING))

    sb.append(parameters.remove(EXPIRE).map(v => {
      if (!isRowTable) {
        throw Utils.analysisException(
          "Expiry for Column table is not supported")
      }
      s"$GEM_EXPIRE ENTRY WITH TIMETOLIVE $v ACTION DESTROY"
    }).getOrElse(EMPTY_STRING))

    sb.append("  ENABLE CONCURRENCY CHECKS ")
    sb.toString()
  }

  def getPartitioningColumns(
      parameters: mutable.Map[String, String]): Seq[String] = {
    parameters.get(PARTITION_BY).map(v => {
      v.split(",").toSeq.map(a => a.trim)
    }).getOrElse(Seq.empty[String])
  }

  def validateConnProps(parameters: mutable.Map[String, String]): Unit = {
    parameters.keys.forall(v => {
      val u = Utils.toUpperCase(v)
      if (!u.startsWith(JdbcExtendedUtils.SCHEMADDL_PROPERTY) &&
          !ddlOptions.contains(u)) {
        throw new AnalysisException(
          s"Unknown options $v specified while creating table ")
      }
      true
    })
  }

  def mapCatalystTypes(schema: StructType,
      types: Growable[DataType]): Array[Int] = {
    var i = 0
    val result = new Array[Int](schema.length)
    while (i < schema.length) {
      val field = schema.fields(i)
      val dataType = field.dataType
      if (types != null) {
        types += dataType
      }
      result(i) = dataType match {
        case StringType => STRING_TYPE
        case IntegerType => INT_TYPE
        case LongType =>
          if (field.metadata.contains("binarylong")) BINARY_LONG_TYPE
          else LONG_TYPE
        case ShortType => SHORT_TYPE
        case ByteType => BYTE_TYPE
        case BooleanType => BOOLEAN_TYPE
        case _: DecimalType => DECIMAL_TYPE
        case DoubleType => DOUBLE_TYPE
        case FloatType => FLOAT_TYPE
        case DateType => DATE_TYPE
        case TimestampType => TIMESTAMP_TYPE
        case BinaryType => BINARY_TYPE
        case _: ArrayType => ARRAY_TYPE
        case _: MapType => MAP_TYPE
        case _: StructType => STRUCT_TYPE
        case _ => throw new IllegalArgumentException(
          s"Unsupported field $field")
      }
      i += 1
    }
    result
  }
}
