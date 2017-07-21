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

import java.io.PrintWriter

import scala.util.{Failure, Success, Try}

import org.apache.spark.sql.SnappyContext

object SecurityTestUtil {
  // scalastyle:off println
  var expectedExpCnt = 0;
  var unExpectedExpCnt = 0;
  def runQueries(snc: SnappyContext, queryArray: Array[String], expectExcpCnt: Integer,
      unExpectExcpCnt: Integer, pw: PrintWriter, isGrant: Boolean)
  : Unit = {
    println("Inside eun/queries inside SecurityUtil")
    for (j <- 0 to queryArray.length - 1) {
      Try {
        val actualResult = snc.sql(queryArray(j))
        val result = actualResult.collect()
        pw.println(s"Query executed is " + queryArray(j))
        result.foreach(rs => {
          pw.println(rs.toString)
        })
      }
      match {
        case Success(v) => pw.close()
        case Failure(e) =>
          if (isGrant) {
            unExpectedExpCnt += 1
          }
          else {
            expectedExpCnt += 1
          }
          pw.println("Exception occurred while executing the job " + "\nError Message:" + e
              .getMessage)
          pw.close()
      }
    }
      validate(expectExcpCnt, unExpectExcpCnt)
  }

  def validate(expectedCnt: Integer, unExpectedCnt: Integer) : Unit = {
    if (unExpectedCnt != unExpectedExpCnt){
     sys.error("Validation failure expected cnt was = " + unExpectedCnt + " but got = "
         + unExpectedCnt)
    }
    else {
      println("Validation SUCCESSFUL Got expected cnt of unExpectedException = " + unExpectedCnt)
    }
    if(expectedCnt != expectedExpCnt) {
      sys.error("Validation failure expected cnt was = " + expectedCnt + " but got = "
          + expectedExpCnt)
    }
    else {
      println("Validation SUCCESSFUL Got expected cnt of expectedException = " + expectedCnt)
    }

  }
}
