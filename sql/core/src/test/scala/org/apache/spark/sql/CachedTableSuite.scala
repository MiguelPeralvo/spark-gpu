/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.concurrent.Eventually._

import org.apache.spark.Accumulators
import org.apache.spark.sql.columnar._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.storage.{StorageLevel, RDDBlockId}

private case class BigData(s: String)

class CachedTableSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  def rddIdOf(tableName: String): Int = {
    val executedPlan = sqlContext.table(tableName).queryExecution.executedPlan
    executedPlan.collect {
      case InMemoryColumnarTableScan(_, _, relation) =>
        relation.cachedColumnBuffers.id
      case _ =>
        fail(s"Table $tableName is not cached\n" + executedPlan)
    }.head
  }

  def isMaterialized(rddId: Int): Boolean = {
    sparkContext.env.blockManager.get(RDDBlockId(rddId, 0)).nonEmpty
  }

  test("withColumn doesn't invalidate cached dataframe") {
    var evalCount = 0
    val myUDF = udf((x: String) => { evalCount += 1; "result" })
    val df = Seq(("test", 1)).toDF("s", "i").select(myUDF($"s"))
    df.cache()

    df.collect()
    assert(evalCount === 1)

    df.collect()
    assert(evalCount === 1)

    val df2 = df.withColumn("newColumn", lit(1))
    df2.collect()

    // We should not reevaluate the cached dataframe
    assert(evalCount === 1)
  }

  test("cache temp table") {
    testData.select('key).registerTempTable("tempTable")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"), 0)
    sqlContext.cacheTable("tempTable")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    sqlContext.uncacheTable("tempTable")
  }

  test("unpersist an uncached table will not raise exception") {
    assert(None == sqlContext.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == sqlContext.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == sqlContext.cacheManager.lookupCachedData(testData))
    testData.persist()
    assert(None != sqlContext.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == sqlContext.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == sqlContext.cacheManager.lookupCachedData(testData))
  }

  test("cache table as select") {
    sql("CACHE TABLE tempTable AS SELECT key FROM testData")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    sqlContext.uncacheTable("tempTable")
  }

  test("uncaching temp table") {
    testData.select('key).registerTempTable("tempTable1")
    testData.select('key).registerTempTable("tempTable2")
    sqlContext.cacheTable("tempTable1")

    assertCached(sql("SELECT COUNT(*) FROM tempTable1"))
    assertCached(sql("SELECT COUNT(*) FROM tempTable2"))

    // Is this valid?
    sqlContext.uncacheTable("tempTable2")

    // Should this be cached?
    assertCached(sql("SELECT COUNT(*) FROM tempTable1"), 0)
  }

  test("too big for memory") {
    val data = "*" * 1000
    sparkContext.parallelize(1 to 200000, 1).map(_ => BigData(data)).toDF()
      .registerTempTable("bigData")
    sqlContext.table("bigData").persist(StorageLevel.MEMORY_AND_DISK)
    assert(sqlContext.table("bigData").count() === 200000L)
    sqlContext.table("bigData").unpersist(blocking = true)
  }

  test("calling .cache() should use in-memory columnar caching") {
    sqlContext.table("testData").cache()
    assertCached(sqlContext.table("testData"))
    sqlContext.table("testData").unpersist(blocking = true)
  }

  test("calling .unpersist() should drop in-memory columnar cache") {
    sqlContext.table("testData").cache()
    sqlContext.table("testData").count()
    sqlContext.table("testData").unpersist(blocking = true)
    assertCached(sqlContext.table("testData"), 0)
  }

  test("isCached") {
    sqlContext.cacheTable("testData")

    assertCached(sqlContext.table("testData"))
    assert(sqlContext.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => true
      case _ => false
    })

    sqlContext.uncacheTable("testData")
    assert(!sqlContext.isCached("testData"))
    assert(sqlContext.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => false
      case _ => true
    })
  }

  test("SPARK-1669: cacheTable should be idempotent") {
    assume(!sqlContext.table("testData").logicalPlan.isInstanceOf[InMemoryRelation])

    sqlContext.cacheTable("testData")
    assertCached(sqlContext.table("testData"))

    assertResult(1, "InMemoryRelation not found, testData should have been cached") {
      sqlContext.table("testData").queryExecution.withCachedData.collect {
        case r: InMemoryRelation => r
      }.size
    }

    sqlContext.cacheTable("testData")
    assertResult(0, "Double InMemoryRelations found, cacheTable() is not idempotent") {
      sqlContext.table("testData").queryExecution.withCachedData.collect {
        case r @ InMemoryRelation(_, _, _, _, _: InMemoryColumnarTableScan, _) => r
      }.size
    }

    sqlContext.uncacheTable("testData")
  }

  test("read from cached table and uncache") {
    sqlContext.cacheTable("testData")
    checkAnswer(sqlContext.table("testData"), testData.collect().toSeq)
    assertCached(sqlContext.table("testData"))

    sqlContext.uncacheTable("testData")
    checkAnswer(sqlContext.table("testData"), testData.collect().toSeq)
    assertCached(sqlContext.table("testData"), 0)
  }

  test("correct error on uncache of non-cached table") {
    intercept[IllegalArgumentException] {
      sqlContext.uncacheTable("testData")
    }
  }

  test("SELECT star from cached table") {
    sql("SELECT * FROM testData").registerTempTable("selectStar")
    sqlContext.cacheTable("selectStar")
    checkAnswer(
      sql("SELECT * FROM selectStar WHERE key = 1"),
      Seq(Row(1, "1")))
    sqlContext.uncacheTable("selectStar")
  }

  test("Self-join cached") {
    val unCachedAnswer =
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key").collect()
    sqlContext.cacheTable("testData")
    checkAnswer(
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key"),
      unCachedAnswer.toSeq)
    sqlContext.uncacheTable("testData")
  }

  test("'CACHE TABLE' and 'UNCACHE TABLE' SQL statement") {
    sql("CACHE TABLE testData")
    assertCached(sqlContext.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sql("UNCACHE TABLE testData")
    assert(!sqlContext.isCached("testData"), "Table 'testData' should not be cached")

    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE TABLE tableName AS SELECT * FROM anotherTable") {
    sql("CACHE TABLE testCacheTable AS SELECT * FROM testData")
    assertCached(sqlContext.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sqlContext.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE TABLE tableName AS SELECT ...") {
    sql("CACHE TABLE testCacheTable AS SELECT key FROM testData LIMIT 10")
    assertCached(sqlContext.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sqlContext.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("CACHE LAZY TABLE tableName") {
    sql("CACHE LAZY TABLE testData")
    assertCached(sqlContext.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      !isMaterialized(rddId),
      "Lazily cached in-memory table shouldn't be materialized eagerly")

    sql("SELECT COUNT(*) FROM testData").collect()
    assert(
      isMaterialized(rddId),
      "Lazily cached in-memory table should have been materialized")

    sqlContext.uncacheTable("testData")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("InMemoryRelation statistics") {
    sql("CACHE TABLE testData")
    sqlContext.table("testData").queryExecution.withCachedData.collect {
      case cached: InMemoryRelation =>
        val actualSizeInBytes = (1 to 100).map(i => INT.defaultSize + i.toString.length + 4).sum
        assert(cached.statistics.sizeInBytes === actualSizeInBytes)
    }
  }

  test("Drops temporary table") {
    testData.select('key).registerTempTable("t1")
    sqlContext.table("t1")
    sqlContext.dropTempTable("t1")
    assert(
      intercept[RuntimeException](sqlContext.table("t1")).getMessage.startsWith("Table Not Found"))
  }

  test("Drops cached temporary table") {
    testData.select('key).registerTempTable("t1")
    testData.select('key).registerTempTable("t2")
    sqlContext.cacheTable("t1")

    assert(sqlContext.isCached("t1"))
    assert(sqlContext.isCached("t2"))

    sqlContext.dropTempTable("t1")
    assert(
      intercept[RuntimeException](sqlContext.table("t1")).getMessage.startsWith("Table Not Found"))
    assert(!sqlContext.isCached("t2"))
  }

  test("Clear all cache") {
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    sqlContext.cacheTable("t1")
    sqlContext.cacheTable("t2")
    sqlContext.clearCache()
    assert(sqlContext.cacheManager.isEmpty)

    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    sqlContext.cacheTable("t1")
    sqlContext.cacheTable("t2")
    sql("Clear CACHE")
    assert(sqlContext.cacheManager.isEmpty)
  }

  test("Clear accumulators when uncacheTable to prevent memory leaking") {
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")

    sqlContext.cacheTable("t1")
    sqlContext.cacheTable("t2")

    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()
    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()

    Accumulators.synchronized {
      val accsSize = Accumulators.originals.size
      sqlContext.uncacheTable("t1")
      sqlContext.uncacheTable("t2")
      assert((accsSize - 2) == Accumulators.originals.size)
    }
  }
}
