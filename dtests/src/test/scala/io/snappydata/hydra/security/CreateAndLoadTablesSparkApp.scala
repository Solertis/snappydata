/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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
package io.snappydata.hydra.security

import java.io.{File, FileOutputStream, PrintWriter}

import io.snappydata.hydra.northwind.{NWQueries, NWTestUtil}

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SnappyContext

object CreateAndLoadTablesSparkApp {

  def main(args: Array[String]) {
    // scalastyle:off println
    val connectionURL = args(args.length - 1)
    println("The connection url is " + connectionURL)
    val conf = new SparkConf().
        setAppName("CreateAndLoadNWTablesSpark Application").
        set("snappydata.connection", connectionURL)
    val sc = SparkContext.getOrCreate(conf)
    val snc = SnappyContext(sc)

    val pw = new PrintWriter(new FileOutputStream(new File("CreateAndLoadNWTablesSparkApp.out"),
      true));
  /*  val dataFilesLocation = args(0)
    // snc.sql("set spark.sql.shuffle.partitions=6")
    snc.setConf("dataFilesLocation", dataFilesLocation)
    NWQueries.snc = snc
    NWQueries.dataFilesLocation = dataFilesLocation
    val tableType = args(1)

    pw.println(s"dataFilesLocation : ${dataFilesLocation}")
    NWTestUtil.dropTables(snc)
    pw.println(s"Create and load ${tableType} tables Test started at : " + System.currentTimeMillis)
    tableType match {
      case "ReplicatedRow" => NWTestUtil.createAndLoadReplicatedTables(snc)
      case "PartitionedRow" => NWTestUtil.createAndLoadPartitionedTables(snc)
      case "Column" => NWTestUtil.createAndLoadColumnTables(snc)
      case "Colocated" => NWTestUtil.createAndLoadColocatedTables(snc)
      case _ => // the default, catch-all
    }
    pw.println(s"Create and load ${tableType} tables Test completed successfully at : " + System
        .currentTimeMillis) */
    println("Getting users arguments")
    val queryFile = args(0)
    val queryArray = scala.io.Source.fromFile(queryFile).getLines().mkString.split(";")
    val isGrant = args(1).toBoolean
    val userSchema = args(2)
   // val result = userSchema.split('.')
   // val tableOwner = result(0)
    println("The arguments are queryFile " + queryFile + " isGrant = " +
        isGrant + "userSchema = " + userSchema)
    pw.println("The arguments are queryFile " + queryFile + " isGrant = " +
        isGrant + "userSchema = " + userSchema)

    val expectedExcptCnt = args(3).toInt
    val unExpectedExcptCnt = args(4).toInt
   // val currentUser = args(7)
    // if(isGrant){

      snc.sql("SELECT count(*) from " + userSchema).show
      SecurityTestUtil.runQueries(snc, queryArray, expectedExcptCnt, unExpectedExcptCnt,
        pw, isGrant)
    // }
    pw.close()
  }
}
