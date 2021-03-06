/*
 * Copyright 2015 Kakao Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kakao.hbase;

import com.kakao.hbase.common.Args;
import com.kakao.hbase.common.HBaseClient;
import com.kakao.hbase.common.InvalidTableException;
import com.kakao.hbase.common.util.Util;
import com.kakao.hbase.specific.CommandAdapter;
import com.kakao.hbase.specific.HBaseAdminWrapper;
import joptsimple.OptionParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.access.AccessControlLists;
import org.apache.hadoop.hbase.security.access.SecureTestUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.*;
import org.junit.rules.TestName;

import java.io.IOException;
import java.util.*;

@SuppressWarnings("unused")
public class TestBase extends SecureTestUtil {
    protected static final String TEST_TABLE_CF = "d";
    protected static final String TEST_TABLE_CF2 = "e";
    protected static final String TEST_NAMESPACE = "unit_test";
    protected static final int MAX_WAIT_ITERATION = 200;
    protected static final long WAIT_INTERVAL = 100;
    private static final List<String> additionalTables = new ArrayList<>();
    private static final Log LOG = LogFactory.getLog(TestBase.class);
    protected static int RS_COUNT = 2;
    protected static HBaseAdmin admin = null;
    protected static Configuration conf = null;
    protected static String tableName;
    protected static HConnection hConnection;
    protected static boolean miniCluster = false;
    protected static boolean securedCluster = false;
    protected static User USER_RW = null;
    protected static HBaseTestingUtility hbase = null;
    private static Map<String, Map<String, Long>> tableServerWriteRequestMap = new HashMap<>();
    private static boolean previousBalancerRunning = true;
    private static ArrayList<ServerName> serverNameList = null;
    private static boolean testNamespaceCreated = false;
    public final String tablePrefix;
    @Rule
    public final TestName testName = new TestName();

    public TestBase(Class c) {
        tablePrefix = "UNIT_TEST_" + c.getSimpleName();
    }

    @BeforeClass
    public static void setUpOnce() throws Exception {
        miniCluster = System.getProperty("cluster.type").equals("mini");
        securedCluster = System.getProperty("cluster.secured").equals("true");
        System.out.println("realCluster - " + !miniCluster);
        System.out.println("securedCluster - " + securedCluster);

        Util.setLoggingThreshold("ERROR");

        if (miniCluster) {
            if (hbase == null) {
                hbase = new HBaseTestingUtility();
                conf = hbase.getConfiguration();
                conf.set("zookeeper.session.timeout", "3600000");
                conf.set("dfs.client.socket-timeout", "3600000");

                if (securedCluster) {
                    hbase.startMiniCluster(RS_COUNT);
                    hbase.waitTableEnabled(AccessControlLists.ACL_TABLE_NAME, 30000L);
                    admin = new HBaseAdminWrapper(conf);
                } else {
                    hbase.startMiniCluster(RS_COUNT);
                    admin = hbase.getHBaseAdmin();
                }
            }
        } else {
            if (admin == null) {
                final String argsFileName = securedCluster ? "../../testClusterRealSecured.args" : "../../testClusterRealNonSecured.args";
                if (!Util.isFile(argsFileName)) {
                    throw new IllegalStateException("You have to define args file " + argsFileName + " for tests.");
                }

                String[] testArgs = {argsFileName};
                Args args = new TestArgs(testArgs);
                admin = HBaseClient.getAdmin(args);
                conf = admin.getConfiguration();
                RS_COUNT = getServerNameList().size();
            }
        }
        previousBalancerRunning = admin.setBalancerRunning(false, true);
        hConnection = HConnectionManager.createConnection(conf);

        USER_RW = User.createUserForTesting(conf, "rwuser", new String[0]);
    }

    @AfterClass
    public static void tearDownOnce() throws Exception {
        if (!miniCluster) {
            if (previousBalancerRunning) {
                if (admin != null)
                    admin.setBalancerRunning(true, true);
            }
        }
    }

    protected static void validateTable(HBaseAdmin admin, String tableName) throws IOException, InterruptedException {
        if (tableName.equals(Args.ALL_TABLES)) return;

        boolean tableExists = admin.tableExists(tableName);
        if (tableExists) {
            if (!admin.isTableEnabled(tableName)) {
                throw new InvalidTableException("Table is not enabled.");
            }
        } else {
            throw new InvalidTableException("Table does not exist.");
        }
    }

    protected static List<HRegionInfo> getRegionInfoList(ServerName serverName, String tableName) throws IOException {
        List<HRegionInfo> onlineRegions = new ArrayList<>();
        for (HRegionInfo onlineRegion : CommandAdapter.getOnlineRegions(null, admin, serverName)) {
            if (onlineRegion.getTableNameAsString().equals(tableName)) {
                onlineRegions.add(onlineRegion);
            }
        }
        return onlineRegions;
    }

    protected static ArrayList<ServerName> getServerNameList() throws IOException {
        if (TestBase.serverNameList == null) {
            Set<ServerName> serverNameSet = new TreeSet<>(admin.getClusterStatus().getServers());
            ArrayList<ServerName> serverNameList = new ArrayList<>();
            for (ServerName serverName : serverNameSet) {
                serverNameList.add(serverName);
            }
            TestBase.serverNameList = serverNameList;
        }
        return TestBase.serverNameList;
    }

    @Before
    public void setUp() throws Exception {
        additionalTables.clear();
        tableName = tablePrefix + "_" + testName.getMethodName();
        recreateTable(tableName);
    }

    @After
    public void tearDown() throws Exception {
        dropTable(tableName);
        for (String additionalTable : additionalTables) {
            dropTable(additionalTable);
        }
    }

    protected void dropTable(String tableName) throws IOException {
        if (admin.tableExists(tableName)) {
            try {
                admin.disableTable(tableName);
            } catch (TableNotEnabledException ignored) {
            }
            admin.deleteTable(tableName);
        }
    }

    protected void recreateTable(String tableName) throws Exception {
        dropTable(tableName);
        createTable(tableName);
    }

    protected String createAdditionalTable(String tableName) throws Exception {
        recreateTable(tableName);
        additionalTables.add(tableName);
        return tableName;
    }

    protected void splitTable(byte[] splitPoint) throws Exception {
        splitTable(tableName, splitPoint);
    }

    protected void splitTable(String tableName, byte[] splitPoint) throws Exception {
        int regionCount = getRegionCount(tableName);
        admin.split(tableName.getBytes(), splitPoint);
        waitForSplitting(tableName, regionCount + 1);
    }

    protected void waitForSplitting(int regionCount) throws IOException, InterruptedException {
        waitForSplitting(tableName, regionCount);
    }

    protected void waitForSplitting(String tableName, int regionCount) throws IOException, InterruptedException {
        int regionCountActual = 0;
        for (int i = 0; i < MAX_WAIT_ITERATION; i++) {
            try (HTable table = new HTable(conf, tableName)) {
                regionCountActual = 0;
                NavigableMap<HRegionInfo, ServerName> regionLocations = table.getRegionLocations();
                for (Map.Entry<HRegionInfo, ServerName> entry : regionLocations.entrySet()) {
                    HServerLoad serverLoad = admin.getClusterStatus().getLoad(entry.getValue());
                    for (HServerLoad.RegionLoad regionLoad : serverLoad.getRegionsLoad().values()) {
                        if (Arrays.equals(entry.getKey().getRegionName(), regionLoad.getName()))
                            regionCountActual++;
                    }
                }
                if (regionCountActual == regionCount) {
                    return;
                }
            }
            Thread.sleep(WAIT_INTERVAL);
        }

        Assert.assertEquals(getMethodName() + " failed - ", regionCount, regionCountActual);
    }

    protected void createTable(String tableName) throws Exception {
        HTableDescriptor td = new HTableDescriptor(tableName.getBytes());
        HColumnDescriptor cd = new HColumnDescriptor(TEST_TABLE_CF.getBytes());
        td.addFamily(cd);
        admin.createTable(td);
        LOG.info(tableName + " table is successfully created.");
    }

    protected int getRegionCount(String tableName) throws IOException {
        return admin.getTableRegions(tableName.getBytes()).size();
    }

    protected ArrayList<HRegionInfo> getRegionInfoList(String tableName) throws IOException {
        Set<HRegionInfo> hRegionInfoSet = new TreeSet<>();
        try (HTable table = new HTable(conf, tableName)) {
            hRegionInfoSet.addAll(table.getRegionLocations().keySet());
        }
        return new ArrayList<>(hRegionInfoSet);
    }

    private String getMethodName() {
        return Thread.currentThread().getStackTrace()[1].getMethodName();
    }

    private Long getWriteRequestMetric(String tableName, ServerName serverName) {
        Map<String, Long> serverMap = tableServerWriteRequestMap.get(tableName);
        if (serverMap == null) {
            serverMap = new HashMap<>();
            tableServerWriteRequestMap.put(tableName, serverMap);
        }

        Long writeRequest = serverMap.get(serverName.getServerName());
        if (writeRequest == null) {
            writeRequest = 0L;
            serverMap.put(serverName.getServerName(), writeRequest);
        }
        return writeRequest;
    }

    protected void waitForDisabled(String tableName) throws Exception {
        for (int i = 0; i < MAX_WAIT_ITERATION; i++) {
            if (admin.isTableDisabled(tableName)) {
                return;
            }
            Thread.sleep(WAIT_INTERVAL);
        }
        Assert.fail(getMethodName() + " failed");
    }

    protected void waitForEnabled(String tableName) throws Exception {
        for (int i = 0; i < MAX_WAIT_ITERATION; i++) {
            if (admin.isTableEnabled(tableName)) {
                return;
            }
            Thread.sleep(WAIT_INTERVAL);
        }
        Assert.fail(getMethodName() + " failed");
    }

    protected void waitForDelete(String tableName) throws Exception {
        for (int i = 0; i < MAX_WAIT_ITERATION; i++) {
            if (!admin.tableExists(tableName)) {
                return;
            }
            Thread.sleep(WAIT_INTERVAL);
        }
        Assert.fail(getMethodName() + " failed");
    }

    public static class TestArgs extends Args {
        public TestArgs(String[] args) throws IOException {
            super(args);
        }

        @Override
        protected OptionParser createOptionParser() {
            return createCommonOptionParser();
        }
    }

    protected void mergeRegion(HRegionInfo regionA, HRegionInfo regionB) throws IOException, InterruptedException {
        mergeRegion(tableName, regionA, regionB);
    }

    protected void mergeRegion(String tableName, HRegionInfo regionA, HRegionInfo regionB) throws IOException, InterruptedException {
        throw new IllegalStateException("Not supported in this version");
    }

    protected void move(HRegionInfo regionInfo, ServerName serverName) throws Exception {
        admin.move(regionInfo.getEncodedName().getBytes(), serverName.getServerName().getBytes());
        waitForMoving(regionInfo, serverName);
    }

    protected void waitForMoving(HRegionInfo hRegionInfo, ServerName serverName) throws Exception {
        Map<byte[], HServerLoad.RegionLoad> regionsLoad = null;
        for (int i = 0; i < MAX_WAIT_ITERATION; i++) {
            HServerLoad load = admin.getClusterStatus().getLoad(serverName);
            regionsLoad = load.getRegionsLoad();
            for (byte[] regionName : regionsLoad.keySet()) {
                if (Arrays.equals(regionName, hRegionInfo.getRegionName())) return;
            }
            admin.move(hRegionInfo.getEncodedNameAsBytes(), serverName.getServerName().getBytes());
            Thread.sleep(WAIT_INTERVAL);
        }

        System.out.println("hRegionInfo = " + Bytes.toString(hRegionInfo.getRegionName()));
        for (Map.Entry<byte[], HServerLoad.RegionLoad> entry : regionsLoad.entrySet()) {
            System.out.println("regionsLoad = " + Bytes.toString(entry.getKey()) + " - " + entry.getValue());
        }

        Assert.fail(Util.getMethodName() + " failed");
    }

    protected HServerLoad.RegionLoad getRegionLoad(HRegionInfo regionInfo, ServerName serverName) throws IOException {
        HServerLoad serverLoad = admin.getClusterStatus().getLoad(serverName);
        Map<byte[], HServerLoad.RegionLoad> regionsLoad = serverLoad.getRegionsLoad();
        for (Map.Entry<byte[], HServerLoad.RegionLoad> entry : regionsLoad.entrySet()) {
            if (Arrays.equals(entry.getKey(), regionInfo.getRegionName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    protected HTable getTable(String tableName) throws IOException {
        return new HTable(conf, tableName);
    }
}
