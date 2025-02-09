/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.wal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.protobuf.generated.ZooKeeperProtos.SplitLogTask.RecoveryMode;
import org.apache.hadoop.hbase.regionserver.DefaultStoreEngine;
import org.apache.hadoop.hbase.regionserver.DefaultStoreFlusher;
import org.apache.hadoop.hbase.regionserver.FlushRequestListener;
import org.apache.hadoop.hbase.regionserver.FlushRequester;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.MemStoreSnapshot;
import org.apache.hadoop.hbase.regionserver.MultiVersionConcurrencyControl;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.throttle.ThroughputController;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.RegionServerTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdge;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.HFileTestUtil;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.wal.DefaultWALProvider;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.hadoop.hbase.wal.WALSplitter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.mockito.Mockito;

/**
 * Test replay of edits out of a WAL split.
 */
@Category({RegionServerTests.class, MediumTests.class})
public class TestWALReplay {
  private static final Log LOG = LogFactory.getLog(TestWALReplay.class);
  static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private final EnvironmentEdge ee = EnvironmentEdgeManager.getDelegate();
  private Path hbaseRootDir = null;
  private String logName;
  private Path oldLogDir;
  private Path logDir;
  private FileSystem fs;
  private Configuration conf;
  private RecoveryMode mode;
  private WALFactory wals;

  @Rule
  public final TestName currentTest = new TestName();


  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.setBoolean("dfs.support.append", true);
    // The below config supported by 0.20-append and CDH3b2
    conf.setInt("dfs.client.block.recovery.retries", 2);
    TEST_UTIL.startMiniCluster(3);
    Path hbaseRootDir =
      TEST_UTIL.getDFSCluster().getFileSystem().makeQualified(new Path("/hbase"));
    LOG.info("hbase.rootdir=" + hbaseRootDir);
    FSUtils.setRootDir(conf, hbaseRootDir);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    this.conf = HBaseConfiguration.create(TEST_UTIL.getConfiguration());
    this.fs = TEST_UTIL.getDFSCluster().getFileSystem();
    this.hbaseRootDir = FSUtils.getRootDir(this.conf);
    this.oldLogDir = new Path(this.hbaseRootDir, HConstants.HREGION_OLDLOGDIR_NAME);
    this.logName = DefaultWALProvider.getWALDirectoryName(currentTest.getMethodName() + "-manual");
    this.logDir = new Path(this.hbaseRootDir, logName);
    if (TEST_UTIL.getDFSCluster().getFileSystem().exists(this.hbaseRootDir)) {
      TEST_UTIL.getDFSCluster().getFileSystem().delete(this.hbaseRootDir, true);
    }
    this.mode = (conf.getBoolean(HConstants.DISTRIBUTED_LOG_REPLAY_KEY, false) ?
        RecoveryMode.LOG_REPLAY : RecoveryMode.LOG_SPLITTING);
    this.wals = new WALFactory(conf, null, currentTest.getMethodName());
  }

  @After
  public void tearDown() throws Exception {
    this.wals.close();
    TEST_UTIL.getDFSCluster().getFileSystem().delete(this.hbaseRootDir, true);
  }

  /*
   * @param p Directory to cleanup
   */
  private void deleteDir(final Path p) throws IOException {
    if (this.fs.exists(p)) {
      if (!this.fs.delete(p, true)) {
        throw new IOException("Failed remove of " + p);
      }
    }
  }

  /**
   *
   * @throws Exception
   */
  @Test
  public void testReplayEditsAfterRegionMovedWithMultiCF() throws Exception {
    final TableName tableName =
        TableName.valueOf("testReplayEditsAfterRegionMovedWithMultiCF");
    byte[] family1 = Bytes.toBytes("cf1");
    byte[] family2 = Bytes.toBytes("cf2");
    byte[] qualifier = Bytes.toBytes("q");
    byte[] value = Bytes.toBytes("testV");
    byte[][] familys = { family1, family2 };
    TEST_UTIL.createTable(tableName, familys);
    Table htable = TEST_UTIL.getConnection().getTable(tableName);
    Put put = new Put(Bytes.toBytes("r1"));
    put.addColumn(family1, qualifier, value);
    htable.put(put);
    ResultScanner resultScanner = htable.getScanner(new Scan());
    int count = 0;
    while (resultScanner.next() != null) {
      count++;
    }
    resultScanner.close();
    assertEquals(1, count);

    MiniHBaseCluster hbaseCluster = TEST_UTIL.getMiniHBaseCluster();
    List<HRegion> regions = hbaseCluster.getRegions(tableName);
    assertEquals(1, regions.size());

    // move region to another regionserver
    Region destRegion = regions.get(0);
    int originServerNum = hbaseCluster
        .getServerWith(destRegion.getRegionInfo().getRegionName());
    assertTrue("Please start more than 1 regionserver", hbaseCluster
        .getRegionServerThreads().size() > 1);
    int destServerNum = 0;
    while (destServerNum == originServerNum) {
      destServerNum++;
    }
    HRegionServer originServer = hbaseCluster.getRegionServer(originServerNum);
    HRegionServer destServer = hbaseCluster.getRegionServer(destServerNum);
    // move region to destination regionserver
    moveRegionAndWait(destRegion, destServer);

    // delete the row
    Delete del = new Delete(Bytes.toBytes("r1"));
    htable.delete(del);
    resultScanner = htable.getScanner(new Scan());
    count = 0;
    while (resultScanner.next() != null) {
      count++;
    }
    resultScanner.close();
    assertEquals(0, count);

    // flush region and make major compaction
    Region region =  destServer.getOnlineRegion(destRegion.getRegionInfo().getRegionName());
    region.flush(true);
    // wait to complete major compaction
    for (Store store : region.getStores()) {
      store.triggerMajorCompaction();
    }
    region.compact(true);

    // move region to origin regionserver
    moveRegionAndWait(destRegion, originServer);
    // abort the origin regionserver
    originServer.abort("testing");

    // see what we get
    Result result = htable.get(new Get(Bytes.toBytes("r1")));
    if (result != null) {
      assertTrue("Row is deleted, but we get" + result.toString(),
          (result == null) || result.isEmpty());
    }
    resultScanner.close();
  }

  private void moveRegionAndWait(Region destRegion, HRegionServer destServer)
      throws InterruptedException, MasterNotRunningException,
      ZooKeeperConnectionException, IOException {
    HMaster master = TEST_UTIL.getMiniHBaseCluster().getMaster();
    TEST_UTIL.getHBaseAdmin().move(
        destRegion.getRegionInfo().getEncodedNameAsBytes(),
        Bytes.toBytes(destServer.getServerName().getServerName()));
    while (true) {
      ServerName serverName = master.getAssignmentManager()
        .getRegionStates().getRegionServerOfRegion(destRegion.getRegionInfo());
      if (serverName != null && serverName.equals(destServer.getServerName())) {
        TEST_UTIL.assertRegionOnServer(
          destRegion.getRegionInfo(), serverName, 200);
        break;
      }
      Thread.sleep(10);
    }
  }

  /**
   * Tests for hbase-2727.
   * @throws Exception
   * @see <a href="https://issues.apache.org/jira/browse/HBASE-2727">HBASE-2727</a>
   */
  @Test
  public void test2727() throws Exception {
    // Test being able to have > 1 set of edits in the recovered.edits directory.
    // Ensure edits are replayed properly.
    final TableName tableName =
        TableName.valueOf("test2727");

    MultiVersionConcurrencyControl mvcc = new MultiVersionConcurrencyControl();
    HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    Path basedir = FSUtils.getTableDir(hbaseRootDir, tableName);
    deleteDir(basedir);

    HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    Region region2 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region2);
    final byte [] rowName = tableName.getName();

    WAL wal1 = createWAL(this.conf);
    // Add 1k to each family.
    final int countPerFamily = 1000;

    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addWALEdits(tableName, hri, rowName, hcd.getName(), countPerFamily, ee,
          wal1, htd, mvcc);
    }
    wal1.shutdown();
    runWALSplit(this.conf);

    WAL wal2 = createWAL(this.conf);
    // Add 1k to each family.
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addWALEdits(tableName, hri, rowName, hcd.getName(), countPerFamily,
          ee, wal2, htd, mvcc);
    }
    wal2.shutdown();
    runWALSplit(this.conf);

    WAL wal3 = createWAL(this.conf);
    try {
      HRegion region = HRegion.openHRegion(this.conf, this.fs, hbaseRootDir, hri, htd, wal3);
      long seqid = region.getOpenSeqNum();
      // The regions opens with sequenceId as 1. With 6k edits, its sequence number reaches 6k + 1.
      // When opened, this region would apply 6k edits, and increment the sequenceId by 1
      assertTrue(seqid > mvcc.getWritePoint());
      assertEquals(seqid - 1, mvcc.getWritePoint());
      LOG.debug("region.getOpenSeqNum(): " + region.getOpenSeqNum() + ", wal3.id: "
          + mvcc.getReadPoint());

      // TODO: Scan all.
      region.close();
    } finally {
      wal3.close();
    }
  }

  /**
   * Test case of HRegion that is only made out of bulk loaded files.  Assert
   * that we don't 'crash'.
   * @throws IOException
   * @throws IllegalAccessException
   * @throws NoSuchFieldException
   * @throws IllegalArgumentException
   * @throws SecurityException
   */
  @Test
  public void testRegionMadeOfBulkLoadedFilesOnly()
  throws IOException, SecurityException, IllegalArgumentException,
      NoSuchFieldException, IllegalAccessException, InterruptedException {
    final TableName tableName =
        TableName.valueOf("testRegionMadeOfBulkLoadedFilesOnly");
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = new Path(this.hbaseRootDir, tableName.getNameAsString());
    deleteDir(basedir);
    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    Region region2 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region2);
    WAL wal = createWAL(this.conf);
    Region region = HRegion.openHRegion(hri, htd, wal, this.conf);

    byte [] family = htd.getFamilies().iterator().next().getName();
    Path f =  new Path(basedir, "hfile");
    HFileTestUtil.createHFile(this.conf, fs, f, family, family, Bytes.toBytes(""),
        Bytes.toBytes("z"), 10);
    List <Pair<byte[],String>>  hfs= new ArrayList<Pair<byte[],String>>(1);
    hfs.add(Pair.newPair(family, f.toString()));
    region.bulkLoadHFiles(hfs, true, null);

    // Add an edit so something in the WAL
    byte [] row = tableName.getName();
    region.put((new Put(row)).addColumn(family, family, family));
    wal.sync();
    final int rowsInsertedCount = 11;

    assertEquals(rowsInsertedCount, getScannedCount(region.getScanner(new Scan())));

    // Now 'crash' the region by stealing its wal
    final Configuration newConf = HBaseConfiguration.create(this.conf);
    User user = HBaseTestingUtility.getDifferentUser(newConf,
        tableName.getNameAsString());
    user.runAs(new PrivilegedExceptionAction() {
      @Override
      public Object run() throws Exception {
        runWALSplit(newConf);
        WAL wal2 = createWAL(newConf);

        HRegion region2 = HRegion.openHRegion(newConf, FileSystem.get(newConf),
          hbaseRootDir, hri, htd, wal2);
        long seqid2 = region2.getOpenSeqNum();
        assertTrue(seqid2 > -1);
        assertEquals(rowsInsertedCount, getScannedCount(region2.getScanner(new Scan())));

        // I can't close wal1.  Its been appropriated when we split.
        region2.close();
        wal2.close();
        return null;
      }
    });
  }

  /**
   * HRegion test case that is made of a major compacted HFile (created with three bulk loaded
   * files) and an edit in the memstore.
   * This is for HBASE-10958 "[dataloss] Bulk loading with seqids can prevent some log entries
   * from being replayed"
   * @throws IOException
   * @throws IllegalAccessException
   * @throws NoSuchFieldException
   * @throws IllegalArgumentException
   * @throws SecurityException
   */
  @Test
  public void testCompactedBulkLoadedFiles()
      throws IOException, SecurityException, IllegalArgumentException,
      NoSuchFieldException, IllegalAccessException, InterruptedException {
    final TableName tableName =
        TableName.valueOf("testCompactedBulkLoadedFiles");
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = new Path(this.hbaseRootDir, tableName.getNameAsString());
    deleteDir(basedir);
    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    HRegion region2 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region2);
    WAL wal = createWAL(this.conf);
    HRegion region = HRegion.openHRegion(hri, htd, wal, this.conf);

    // Add an edit so something in the WAL
    byte [] row = tableName.getName();
    byte [] family = htd.getFamilies().iterator().next().getName();
    region.put((new Put(row)).addColumn(family, family, family));
    wal.sync();

    List <Pair<byte[],String>>  hfs= new ArrayList<Pair<byte[],String>>(1);
    for (int i = 0; i < 3; i++) {
      Path f = new Path(basedir, "hfile"+i);
      HFileTestUtil.createHFile(this.conf, fs, f, family, family, Bytes.toBytes(i + "00"),
          Bytes.toBytes(i + "50"), 10);
      hfs.add(Pair.newPair(family, f.toString()));
    }
    region.bulkLoadHFiles(hfs, true, null);
    final int rowsInsertedCount = 31;
    assertEquals(rowsInsertedCount, getScannedCount(region.getScanner(new Scan())));

    // major compact to turn all the bulk loaded files into one normal file
    region.compact(true);
    assertEquals(rowsInsertedCount, getScannedCount(region.getScanner(new Scan())));

    // Now 'crash' the region by stealing its wal
    final Configuration newConf = HBaseConfiguration.create(this.conf);
    User user = HBaseTestingUtility.getDifferentUser(newConf,
        tableName.getNameAsString());
    user.runAs(new PrivilegedExceptionAction() {
      @Override
      public Object run() throws Exception {
        runWALSplit(newConf);
        WAL wal2 = createWAL(newConf);

        HRegion region2 = HRegion.openHRegion(newConf, FileSystem.get(newConf),
            hbaseRootDir, hri, htd, wal2);
        long seqid2 = region2.getOpenSeqNum();
        assertTrue(seqid2 > -1);
        assertEquals(rowsInsertedCount, getScannedCount(region2.getScanner(new Scan())));

        // I can't close wal1.  Its been appropriated when we split.
        region2.close();
        wal2.close();
        return null;
      }
    });
  }


  /**
   * Test writing edits into an HRegion, closing it, splitting logs, opening
   * Region again.  Verify seqids.
   * @throws IOException
   * @throws IllegalAccessException
   * @throws NoSuchFieldException
   * @throws IllegalArgumentException
   * @throws SecurityException
   */
  @Test
  public void testReplayEditsWrittenViaHRegion()
  throws IOException, SecurityException, IllegalArgumentException,
      NoSuchFieldException, IllegalAccessException, InterruptedException {
    final TableName tableName =
        TableName.valueOf("testReplayEditsWrittenViaHRegion");
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = FSUtils.getTableDir(this.hbaseRootDir, tableName);
    deleteDir(basedir);
    final byte[] rowName = tableName.getName();
    final int countPerFamily = 10;
    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    HRegion region3 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region3);
    // Write countPerFamily edits into the three families.  Do a flush on one
    // of the families during the load of edits so its seqid is not same as
    // others to test we do right thing when different seqids.
    WAL wal = createWAL(this.conf);
    HRegion region = HRegion.openHRegion(this.conf, this.fs, hbaseRootDir, hri, htd, wal);
    long seqid = region.getOpenSeqNum();
    boolean first = true;
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addRegionEdits(rowName, hcd.getName(), countPerFamily, this.ee, region, "x");
      if (first ) {
        // If first, so we have at least one family w/ different seqid to rest.
        region.flush(true);
        first = false;
      }
    }
    // Now assert edits made it in.
    final Get g = new Get(rowName);
    Result result = region.get(g);
    assertEquals(countPerFamily * htd.getFamilies().size(),
      result.size());
    // Now close the region (without flush), split the log, reopen the region and assert that
    // replay of log has the correct effect, that our seqids are calculated correctly so
    // all edits in logs are seen as 'stale'/old.
    region.close(true);
    wal.shutdown();
    runWALSplit(this.conf);
    WAL wal2 = createWAL(this.conf);
    HRegion region2 = HRegion.openHRegion(conf, this.fs, hbaseRootDir, hri, htd, wal2);
    long seqid2 = region2.getOpenSeqNum();
    assertTrue(seqid + result.size() < seqid2);
    final Result result1b = region2.get(g);
    assertEquals(result.size(), result1b.size());

    // Next test.  Add more edits, then 'crash' this region by stealing its wal
    // out from under it and assert that replay of the log adds the edits back
    // correctly when region is opened again.
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addRegionEdits(rowName, hcd.getName(), countPerFamily, this.ee, region2, "y");
    }
    // Get count of edits.
    final Result result2 = region2.get(g);
    assertEquals(2 * result.size(), result2.size());
    wal2.sync();
    final Configuration newConf = HBaseConfiguration.create(this.conf);
    User user = HBaseTestingUtility.getDifferentUser(newConf,
      tableName.getNameAsString());
    user.runAs(new PrivilegedExceptionAction() {
      @Override
      public Object run() throws Exception {
        runWALSplit(newConf);
        FileSystem newFS = FileSystem.get(newConf);
        // Make a new wal for new region open.
        WAL wal3 = createWAL(newConf);
        final AtomicInteger countOfRestoredEdits = new AtomicInteger(0);
        HRegion region3 = new HRegion(basedir, wal3, newFS, newConf, hri, htd, null) {
          @Override
          protected boolean restoreEdit(Store s, Cell cell) {
            boolean b = super.restoreEdit(s, cell);
            countOfRestoredEdits.incrementAndGet();
            return b;
          }
        };
        long seqid3 = region3.initialize();
        Result result3 = region3.get(g);
        // Assert that count of cells is same as before crash.
        assertEquals(result2.size(), result3.size());
        assertEquals(htd.getFamilies().size() * countPerFamily,
          countOfRestoredEdits.get());

        // I can't close wal1.  Its been appropriated when we split.
        region3.close();
        wal3.close();
        return null;
      }
    });
  }

  /**
   * Test that we recover correctly when there is a failure in between the
   * flushes. i.e. Some stores got flushed but others did not.
   *
   * Unfortunately, there is no easy hook to flush at a store level. The way
   * we get around this is by flushing at the region level, and then deleting
   * the recently flushed store file for one of the Stores. This would put us
   * back in the situation where all but that store got flushed and the region
   * died.
   *
   * We restart Region again, and verify that the edits were replayed.
   *
   * @throws IOException
   * @throws IllegalAccessException
   * @throws NoSuchFieldException
   * @throws IllegalArgumentException
   * @throws SecurityException
   */
  @Test
  public void testReplayEditsAfterPartialFlush()
  throws IOException, SecurityException, IllegalArgumentException,
      NoSuchFieldException, IllegalAccessException, InterruptedException {
    final TableName tableName =
        TableName.valueOf("testReplayEditsWrittenViaHRegion");
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = FSUtils.getTableDir(this.hbaseRootDir, tableName);
    deleteDir(basedir);
    final byte[] rowName = tableName.getName();
    final int countPerFamily = 10;
    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    HRegion region3 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region3);
    // Write countPerFamily edits into the three families.  Do a flush on one
    // of the families during the load of edits so its seqid is not same as
    // others to test we do right thing when different seqids.
    WAL wal = createWAL(this.conf);
    HRegion region = HRegion.openHRegion(this.conf, this.fs, hbaseRootDir, hri, htd, wal);
    long seqid = region.getOpenSeqNum();
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addRegionEdits(rowName, hcd.getName(), countPerFamily, this.ee, region, "x");
    }

    // Now assert edits made it in.
    final Get g = new Get(rowName);
    Result result = region.get(g);
    assertEquals(countPerFamily * htd.getFamilies().size(),
      result.size());

    // Let us flush the region
    region.flush(true);
    region.close(true);
    wal.shutdown();

    // delete the store files in the second column family to simulate a failure
    // in between the flushcache();
    // we have 3 families. killing the middle one ensures that taking the maximum
    // will make us fail.
    int cf_count = 0;
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      cf_count++;
      if (cf_count == 2) {
        region.getRegionFileSystem().deleteFamily(hcd.getNameAsString());
      }
    }


    // Let us try to split and recover
    runWALSplit(this.conf);
    WAL wal2 = createWAL(this.conf);
    HRegion region2 = HRegion.openHRegion(this.conf, this.fs, hbaseRootDir, hri, htd, wal2);
    long seqid2 = region2.getOpenSeqNum();
    assertTrue(seqid + result.size() < seqid2);

    final Result result1b = region2.get(g);
    assertEquals(result.size(), result1b.size());
  }


  // StoreFlusher implementation used in testReplayEditsAfterAbortingFlush.
  // Only throws exception if throwExceptionWhenFlushing is set true.
  public static class CustomStoreFlusher extends DefaultStoreFlusher {
    // Switch between throw and not throw exception in flush
    static final AtomicBoolean throwExceptionWhenFlushing = new AtomicBoolean(false);

    public CustomStoreFlusher(Configuration conf, Store store) {
      super(conf, store);
    }
    @Override
    public List<Path> flushSnapshot(MemStoreSnapshot snapshot, long cacheFlushId,
        MonitoredTask status, ThroughputController throughputController) throws IOException {
      if (throwExceptionWhenFlushing.get()) {
        throw new IOException("Simulated exception by tests");
      }
      return super.flushSnapshot(snapshot, cacheFlushId, status, throughputController);
    }

  };

  /**
   * Test that we could recover the data correctly after aborting flush. In the
   * test, first we abort flush after writing some data, then writing more data
   * and flush again, at last verify the data.
   * @throws IOException
   */
  @Test
  public void testReplayEditsAfterAbortingFlush() throws IOException {
    final TableName tableName =
        TableName.valueOf("testReplayEditsAfterAbortingFlush");
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = FSUtils.getTableDir(this.hbaseRootDir, tableName);
    deleteDir(basedir);
    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    HRegion region3 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region3);
    // Write countPerFamily edits into the three families. Do a flush on one
    // of the families during the load of edits so its seqid is not same as
    // others to test we do right thing when different seqids.
    WAL wal = createWAL(this.conf);
    RegionServerServices rsServices = Mockito.mock(RegionServerServices.class);
    Mockito.doReturn(false).when(rsServices).isAborted();
    when(rsServices.getServerName()).thenReturn(ServerName.valueOf("foo", 10, 10));
    Configuration customConf = new Configuration(this.conf);
    customConf.set(DefaultStoreEngine.DEFAULT_STORE_FLUSHER_CLASS_KEY,
        CustomStoreFlusher.class.getName());
    HRegion region =
      HRegion.openHRegion(this.hbaseRootDir, hri, htd, wal, customConf, rsServices, null);
    int writtenRowCount = 10;
    List<HColumnDescriptor> families = new ArrayList<HColumnDescriptor>(
        htd.getFamilies());
    for (int i = 0; i < writtenRowCount; i++) {
      Put put = new Put(Bytes.toBytes(tableName + Integer.toString(i)));
      put.addColumn(families.get(i % families.size()).getName(), Bytes.toBytes("q"),
          Bytes.toBytes("val"));
      region.put(put);
    }

    // Now assert edits made it in.
    RegionScanner scanner = region.getScanner(new Scan());
    assertEquals(writtenRowCount, getScannedCount(scanner));

    // Let us flush the region
    CustomStoreFlusher.throwExceptionWhenFlushing.set(true);
    try {
      region.flush(true);
      fail("Injected exception hasn't been thrown");
    } catch (Throwable t) {
      LOG.info("Expected simulated exception when flushing region,"
          + t.getMessage());
      // simulated to abort server
      Mockito.doReturn(true).when(rsServices).isAborted();
      region.setClosing(false); // region normally does not accept writes after
      // DroppedSnapshotException. We mock around it for this test.
    }
    // writing more data
    int moreRow = 10;
    for (int i = writtenRowCount; i < writtenRowCount + moreRow; i++) {
      Put put = new Put(Bytes.toBytes(tableName + Integer.toString(i)));
      put.addColumn(families.get(i % families.size()).getName(), Bytes.toBytes("q"),
          Bytes.toBytes("val"));
      region.put(put);
    }
    writtenRowCount += moreRow;
    // call flush again
    CustomStoreFlusher.throwExceptionWhenFlushing.set(false);
    try {
      region.flush(true);
    } catch (IOException t) {
      LOG.info("Expected exception when flushing region because server is stopped,"
          + t.getMessage());
    }

    region.close(true);
    wal.shutdown();

    // Let us try to split and recover
    runWALSplit(this.conf);
    WAL wal2 = createWAL(this.conf);
    Mockito.doReturn(false).when(rsServices).isAborted();
    HRegion region2 =
      HRegion.openHRegion(this.hbaseRootDir, hri, htd, wal2, this.conf, rsServices, null);
    scanner = region2.getScanner(new Scan());
    assertEquals(writtenRowCount, getScannedCount(scanner));
  }

  private int getScannedCount(RegionScanner scanner) throws IOException {
    int scannedCount = 0;
    List<Cell> results = new ArrayList<Cell>();
    while (true) {
      boolean existMore = scanner.next(results);
      if (!results.isEmpty())
        scannedCount++;
      if (!existMore)
        break;
      results.clear();
    }
    return scannedCount;
  }

  /**
   * Create an HRegion with the result of a WAL split and test we only see the
   * good edits
   * @throws Exception
   */
  @Test
  public void testReplayEditsWrittenIntoWAL() throws Exception {
    final TableName tableName =
        TableName.valueOf("testReplayEditsWrittenIntoWAL");
    final MultiVersionConcurrencyControl mvcc = new MultiVersionConcurrencyControl();
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir = FSUtils.getTableDir(hbaseRootDir, tableName);
    deleteDir(basedir);

    final HTableDescriptor htd = createBasic3FamilyHTD(tableName);
    HRegion region2 = HBaseTestingUtility.createRegionAndWAL(hri, hbaseRootDir, this.conf, htd);
    HBaseTestingUtility.closeRegionAndWAL(region2);
    final WAL wal = createWAL(this.conf);
    final byte[] rowName = tableName.getName();
    final byte[] regionName = hri.getEncodedNameAsBytes();

    // Add 1k to each family.
    final int countPerFamily = 1000;
    Set<byte[]> familyNames = new HashSet<byte[]>();
    for (HColumnDescriptor hcd: htd.getFamilies()) {
      addWALEdits(tableName, hri, rowName, hcd.getName(), countPerFamily,
          ee, wal, htd, mvcc);
      familyNames.add(hcd.getName());
    }

    // Add a cache flush, shouldn't have any effect
    wal.startCacheFlush(regionName, familyNames);
    wal.completeCacheFlush(regionName);

    // Add an edit to another family, should be skipped.
    WALEdit edit = new WALEdit();
    long now = ee.currentTime();
    edit.add(new KeyValue(rowName, Bytes.toBytes("another family"), rowName,
      now, rowName));
    wal.append(htd, hri, new WALKey(hri.getEncodedNameAsBytes(), tableName, now, mvcc), edit, true);

    // Delete the c family to verify deletes make it over.
    edit = new WALEdit();
    now = ee.currentTime();
    edit.add(new KeyValue(rowName, Bytes.toBytes("c"), null, now, KeyValue.Type.DeleteFamily));
    wal.append(htd, hri, new WALKey(hri.getEncodedNameAsBytes(), tableName, now, mvcc), edit, true);

    // Sync.
    wal.sync();
    // Make a new conf and a new fs for the splitter to run on so we can take
    // over old wal.
    final Configuration newConf = HBaseConfiguration.create(this.conf);
    User user = HBaseTestingUtility.getDifferentUser(newConf,
      ".replay.wal.secondtime");
    user.runAs(new PrivilegedExceptionAction() {
      @Override
      public Object run() throws Exception {
        runWALSplit(newConf);
        FileSystem newFS = FileSystem.get(newConf);
        // 100k seems to make for about 4 flushes during HRegion#initialize.
        newConf.setInt(HConstants.HREGION_MEMSTORE_FLUSH_SIZE, 1024 * 100);
        // Make a new wal for new region.
        WAL newWal = createWAL(newConf);
        final AtomicInteger flushcount = new AtomicInteger(0);
        try {
          final HRegion region =
              new HRegion(basedir, newWal, newFS, newConf, hri, htd, null) {
            @Override
            protected FlushResult internalFlushcache(final WAL wal, final long myseqid,
                final Collection<Store> storesToFlush, MonitoredTask status,
                boolean writeFlushWalMarker)
                    throws IOException {
              LOG.info("InternalFlushCache Invoked");
              FlushResult fs = super.internalFlushcache(wal, myseqid, storesToFlush,
                  Mockito.mock(MonitoredTask.class), writeFlushWalMarker);
              flushcount.incrementAndGet();
              return fs;
            }
          };
          // The seq id this region has opened up with
          long seqid = region.initialize();

          // The mvcc readpoint of from inserting data.
          long writePoint = mvcc.getWritePoint();

          // We flushed during init.
          assertTrue("Flushcount=" + flushcount.get(), flushcount.get() > 0);
          assertTrue((seqid - 1) == writePoint);

          Get get = new Get(rowName);
          Result result = region.get(get);
          // Make sure we only see the good edits
          assertEquals(countPerFamily * (htd.getFamilies().size() - 1),
            result.size());
          region.close();
        } finally {
          newWal.close();
        }
        return null;
      }
    });
  }

  @Test
  // the following test is for HBASE-6065
  public void testSequentialEditLogSeqNum() throws IOException {
    final TableName tableName = TableName.valueOf(currentTest.getMethodName());
    final HRegionInfo hri = createBasic3FamilyHRegionInfo(tableName);
    final Path basedir =
        FSUtils.getTableDir(this.hbaseRootDir, tableName);
    deleteDir(basedir);
    final byte[] rowName = tableName.getName();
    final int countPerFamily = 10;
    final HTableDescriptor htd = createBasic1FamilyHTD(tableName);

    // Mock the WAL
    MockWAL wal = createMockWAL();

    HRegion region = HRegion.openHRegion(this.conf, this.fs, hbaseRootDir, hri, htd, wal);
    for (HColumnDescriptor hcd : htd.getFamilies()) {
      addRegionEdits(rowName, hcd.getName(), countPerFamily, this.ee, region, "x");
    }

    // Let us flush the region
    // But this time completeflushcache is not yet done
    region.flush(true);
    for (HColumnDescriptor hcd : htd.getFamilies()) {
      addRegionEdits(rowName, hcd.getName(), 5, this.ee, region, "x");
    }
    long lastestSeqNumber = region.getReadPoint(null);
    // get the current seq no
    wal.doCompleteCacheFlush = true;
    // allow complete cache flush with the previous seq number got after first
    // set of edits.
    wal.completeCacheFlush(hri.getEncodedNameAsBytes());
    wal.shutdown();
    FileStatus[] listStatus = wal.getFiles();
    assertNotNull(listStatus);
    assertTrue(listStatus.length > 0);
    WALSplitter.splitLogFile(hbaseRootDir, listStatus[0],
        this.fs, this.conf, null, null, null, mode, wals);
    FileStatus[] listStatus1 = this.fs.listStatus(
      new Path(FSUtils.getTableDir(hbaseRootDir, tableName), new Path(hri.getEncodedName(),
          "recovered.edits")), new PathFilter() {
        @Override
        public boolean accept(Path p) {
          if (WALSplitter.isSequenceIdFile(p)) {
            return false;
          }
          return true;
        }
      });
    int editCount = 0;
    for (FileStatus fileStatus : listStatus1) {
      editCount = Integer.parseInt(fileStatus.getPath().getName());
    }
    // The sequence number should be same
    assertEquals(
        "The sequence number of the recoverd.edits and the current edit seq should be same",
        lastestSeqNumber, editCount);
  }

  static class MockWAL extends FSHLog {
    boolean doCompleteCacheFlush = false;

    public MockWAL(FileSystem fs, Path rootDir, String logName, Configuration conf)
        throws IOException {
      super(fs, rootDir, logName, HConstants.HREGION_OLDLOGDIR_NAME, conf, null, true, null, null);
    }

    @Override
    public void completeCacheFlush(byte[] encodedRegionName) {
      if (!doCompleteCacheFlush) {
        return;
      }
      super.completeCacheFlush(encodedRegionName);
    }
  }

  private HTableDescriptor createBasic1FamilyHTD(final TableName tableName) {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor a = new HColumnDescriptor(Bytes.toBytes("a"));
    htd.addFamily(a);
    return htd;
  }

  private MockWAL createMockWAL() throws IOException {
    MockWAL wal = new MockWAL(fs, hbaseRootDir, logName, conf);
    // Set down maximum recovery so we dfsclient doesn't linger retrying something
    // long gone.
    HBaseTestingUtility.setMaxRecoveryErrorCount(wal.getOutputStream(), 1);
    return wal;
  }

  // Flusher used in this test.  Keep count of how often we are called and
  // actually run the flush inside here.
  class TestFlusher implements FlushRequester {
    private HRegion r;

    @Override
    public void requestFlush(Region region, boolean force) {
      try {
        r.flush(force);
      } catch (IOException e) {
        throw new RuntimeException("Exception flushing", e);
      }
    }

    @Override
    public void requestDelayedFlush(Region region, long when, boolean forceFlushAllStores) {
      // TODO Auto-generated method stub

    }

    @Override
    public void registerFlushRequestListener(FlushRequestListener listener) {

    }

    @Override
    public boolean unregisterFlushRequestListener(FlushRequestListener listener) {
      return false;
    }

    @Override
    public void setGlobalMemstoreLimit(long globalMemStoreSize) {

    }
  }

  private void addWALEdits(final TableName tableName, final HRegionInfo hri, final byte[] rowName,
      final byte[] family, final int count, EnvironmentEdge ee, final WAL wal,
      final HTableDescriptor htd, final MultiVersionConcurrencyControl mvcc)
  throws IOException {
    String familyStr = Bytes.toString(family);
    for (int j = 0; j < count; j++) {
      byte[] qualifierBytes = Bytes.toBytes(Integer.toString(j));
      byte[] columnBytes = Bytes.toBytes(familyStr + ":" + Integer.toString(j));
      WALEdit edit = new WALEdit();
      edit.add(new KeyValue(rowName, family, qualifierBytes,
        ee.currentTime(), columnBytes));
      wal.append(htd, hri, new WALKey(hri.getEncodedNameAsBytes(), tableName,999, mvcc),
          edit, true);
    }
    wal.sync();
  }

  static List<Put> addRegionEdits (final byte [] rowName, final byte [] family,
      final int count, EnvironmentEdge ee, final Region r,
      final String qualifierPrefix)
  throws IOException {
    List<Put> puts = new ArrayList<Put>();
    for (int j = 0; j < count; j++) {
      byte[] qualifier = Bytes.toBytes(qualifierPrefix + Integer.toString(j));
      Put p = new Put(rowName);
      p.addColumn(family, qualifier, ee.currentTime(), rowName);
      r.put(p);
      puts.add(p);
    }
    return puts;
  }

  /*
   * Creates an HRI around an HTD that has <code>tableName</code> and three
   * column families named 'a','b', and 'c'.
   * @param tableName Name of table to use when we create HTableDescriptor.
   */
   private HRegionInfo createBasic3FamilyHRegionInfo(final TableName tableName) {
    return new HRegionInfo(tableName, null, null, false);
   }

  /*
   * Run the split.  Verify only single split file made.
   * @param c
   * @return The single split file made
   * @throws IOException
   */
  private Path runWALSplit(final Configuration c) throws IOException {
    List<Path> splits = WALSplitter.split(
      hbaseRootDir, logDir, oldLogDir, FileSystem.get(c), c, wals);
    // Split should generate only 1 file since there's only 1 region
    assertEquals("splits=" + splits, 1, splits.size());
    // Make sure the file exists
    assertTrue(fs.exists(splits.get(0)));
    LOG.info("Split file=" + splits.get(0));
    return splits.get(0);
  }

  /*
   * @param c
   * @return WAL with retries set down from 5 to 1 only.
   * @throws IOException
   */
  private WAL createWAL(final Configuration c) throws IOException {
    FSHLog wal = new FSHLog(FileSystem.get(c), hbaseRootDir, logName, c);
    // Set down maximum recovery so we dfsclient doesn't linger retrying something
    // long gone.
    HBaseTestingUtility.setMaxRecoveryErrorCount(wal.getOutputStream(), 1);
    return wal;
  }

  private HTableDescriptor createBasic3FamilyHTD(final TableName tableName) {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor a = new HColumnDescriptor(Bytes.toBytes("a"));
    htd.addFamily(a);
    HColumnDescriptor b = new HColumnDescriptor(Bytes.toBytes("b"));
    htd.addFamily(b);
    HColumnDescriptor c = new HColumnDescriptor(Bytes.toBytes("c"));
    htd.addFamily(c);
    return htd;
  }
}
