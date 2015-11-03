/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.query.continuous;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.MutableEntry;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.CacheAtomicWriteOrderMode;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheEntryEventSerializableFilter;
import org.apache.ignite.cache.CacheEntryProcessor;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.query.ContinuousQuery;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterTopologyException;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.managers.communication.GridIoMessage;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.continuous.GridContinuousHandler;
import org.apache.ignite.internal.processors.continuous.GridContinuousMessage;
import org.apache.ignite.internal.processors.continuous.GridContinuousProcessor;
import org.apache.ignite.internal.util.GridConcurrentHashSet;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.typedef.C1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.PA;
import org.apache.ignite.internal.util.typedef.PAX;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.T3;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.lang.IgniteOutClosure;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.resources.LoggerResource;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.ignite.cache.CacheAtomicWriteOrderMode.PRIMARY;
import static org.apache.ignite.cache.CacheMode.REPLICATED;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;

/**
 *
 */
public abstract class CacheContinuousQueryFailoverAbstractSelfTest extends GridCommonAbstractTest {
    /** */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final int BACKUP_ACK_THRESHOLD = 100;

    /** */
    private static volatile boolean err;

    /** */
    private boolean client;

    /** */
    private int backups = 1;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setForceServerMode(true);
        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(ipFinder);

        TestCommunicationSpi commSpi = new TestCommunicationSpi();

        commSpi.setSharedMemoryPort(-1);
        commSpi.setIdleConnectionTimeout(100);

        cfg.setCommunicationSpi(commSpi);

        CacheConfiguration ccfg = new CacheConfiguration();

        ccfg.setCacheMode(cacheMode());
        ccfg.setAtomicityMode(atomicityMode());
        ccfg.setAtomicWriteOrderMode(writeOrderMode());
        ccfg.setBackups(backups);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);
        ccfg.setNearConfiguration(nearCacheConfiguration());

        cfg.setCacheConfiguration(ccfg);

        cfg.setClientMode(client);

        return cfg;
    }

    /**
     * @return Near cache configuration.
     */
    protected NearCacheConfiguration nearCacheConfiguration() {
        return null;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 5 * 60_000;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        err = false;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();
    }

    /**
     * @return Cache mode.
     */
    protected abstract CacheMode cacheMode();

    /**
     * @return Atomicity mode.
     */
    protected abstract CacheAtomicityMode atomicityMode();

    /**
     * @return Write order mode for atomic cache.
     */
    protected CacheAtomicWriteOrderMode writeOrderMode() {
        return PRIMARY;
    }

    /**
     * @throws Exception If failed.
     */
    public void testFirstFilteredEvent() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClnCache = qryClient.cache(null);

        final CacheEventListener3 lsnr = new CacheEventListener3();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(new CacheEventFilter());

        try (QueryCursor<?> cur = qryClnCache.query(qry)) {
            List<Integer> keys = testKeys(grid(0).cache(null), 1);

            for (Integer key : keys)
                qryClnCache.put(key, -1);

            qryClnCache.put(keys.get(0), 100);
        }

        assertEquals(lsnr.evts.size(), 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testRebalanceVersion() throws Exception {
        Ignite ignite0 = startGrid(0);
        GridDhtPartitionTopology top0 = ((IgniteKernal)ignite0).context().cache().context().cacheContext(1).topology();

        assertTrue(top0.rebalanceFinished(new AffinityTopologyVersion(1)));
        assertFalse(top0.rebalanceFinished(new AffinityTopologyVersion(2)));

        Ignite ignite1 = startGrid(1);
        GridDhtPartitionTopology top1 = ((IgniteKernal)ignite1).context().cache().context().cacheContext(1).topology();

        waitRebalanceFinished(ignite0, 2);
        waitRebalanceFinished(ignite1, 2);

        assertFalse(top0.rebalanceFinished(new AffinityTopologyVersion(3)));
        assertFalse(top1.rebalanceFinished(new AffinityTopologyVersion(3)));

        Ignite ignite2 = startGrid(2);
        GridDhtPartitionTopology top2 = ((IgniteKernal)ignite2).context().cache().context().cacheContext(1).topology();

        waitRebalanceFinished(ignite0, 3);
        waitRebalanceFinished(ignite1, 3);
        waitRebalanceFinished(ignite2, 3);

        assertFalse(top0.rebalanceFinished(new AffinityTopologyVersion(4)));
        assertFalse(top1.rebalanceFinished(new AffinityTopologyVersion(4)));
        assertFalse(top2.rebalanceFinished(new AffinityTopologyVersion(4)));

        client = true;

        Ignite ignite3 = startGrid(3);
        GridDhtPartitionTopology top3 = ((IgniteKernal)ignite3).context().cache().context().cacheContext(1).topology();

        assertTrue(top0.rebalanceFinished(new AffinityTopologyVersion(4)));
        assertTrue(top1.rebalanceFinished(new AffinityTopologyVersion(4)));
        assertTrue(top2.rebalanceFinished(new AffinityTopologyVersion(4)));
        assertTrue(top3.rebalanceFinished(new AffinityTopologyVersion(4)));

        stopGrid(1);

        waitRebalanceFinished(ignite0, 5);
        waitRebalanceFinished(ignite2, 5);
        waitRebalanceFinished(ignite3, 5);

        stopGrid(3);

        assertTrue(top0.rebalanceFinished(new AffinityTopologyVersion(6)));
        assertTrue(top2.rebalanceFinished(new AffinityTopologyVersion(6)));

        stopGrid(0);

        waitRebalanceFinished(ignite2, 7);
    }

    /**
     * @param ignite Ignite.
     * @param topVer Topology version.
     * @throws Exception If failed.
     */
    private void waitRebalanceFinished(Ignite ignite, long topVer) throws Exception {
        final AffinityTopologyVersion topVer0 = new AffinityTopologyVersion(topVer);

        final GridDhtPartitionTopology top =
            ((IgniteKernal)ignite).context().cache().context().cacheContext(1).topology();

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return top.rebalanceFinished(topVer0);
            }
        }, 5000);

        assertTrue(top.rebalanceFinished(topVer0));
    }

    /**
     * @throws Exception If failed.
     */
    public void testOneBackup() throws Exception {
        checkBackupQueue(1, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testOneBackupClientUpdate() throws Exception {
        checkBackupQueue(1, true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testStartStopQuery() throws Exception {
        this.backups = 1;

        final int SRV_NODES = 3;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        final Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> clnCache = qryClient.cache(null);

        IgniteOutClosure<IgniteCache<Integer, Integer>> rndCache =
            new IgniteOutClosure<IgniteCache<Integer, Integer>>() {
                int cnt = 0;

                @Override public IgniteCache<Integer, Integer> apply() {
                    ++cnt;

                    return grid(cnt % SRV_NODES + 1).cache(null);
                }
            };

        Ignite igniteSrv = ignite(0);

        IgniteCache<Object, Object> srvCache = igniteSrv.cache(null);

        List<Integer> keys = testKeys(srvCache, 3);

        int keyCnt = keys.size();

        for (int j = 0; j < 50; ++j) {
            ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

            final CacheEventListener3 lsnr = new CacheEventListener3();

            qry.setLocalListener(lsnr);

            qry.setRemoteFilter(lsnr);

            int keyIter = 0;

            for (; keyIter < keyCnt / 2; keyIter++) {
                int key = keys.get(keyIter);

                rndCache.apply().put(key, key);
            }

            assert lsnr.evts.isEmpty();

            QueryCursor<Cache.Entry<Object, Object>> query = clnCache.query(qry);

            Map<Object, T2<Object, Object>> updates = new HashMap<>();

            final List<T3<Object, Object, Object>> expEvts = new ArrayList<>();

            Affinity<Object> aff = affinity(srvCache);

            boolean filtered = false;

            for (; keyIter < keys.size(); keyIter++) {
                int key = keys.get(keyIter);

                int val = filtered ? 1 : 2;

                log.info("Put [key=" + key + ", val=" + val + ", part=" + aff.partition(key) + ']');

                T2<Object, Object> t = updates.get(key);

                if (t == null) {
                    // Check filtered.
                    if (!filtered) {
                        updates.put(key, new T2<>((Object)val, null));

                        expEvts.add(new T3<>((Object)key, (Object)val, null));
                    }
                }
                else {
                    // Check filtered.
                    if (!filtered) {
                        updates.put(key, new T2<>((Object)val, (Object)t.get1()));

                        expEvts.add(new T3<>((Object)key, (Object)val, (Object)t.get1()));
                    }
                }

                rndCache.apply().put(key, val);

                filtered = !filtered;
            }

            checkEvents(expEvts, lsnr, false);

            query.close();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testLeftPrimaryAndBackupNodes() throws Exception {
        if (cacheMode() == REPLICATED)
            return;

        this.backups = 1;

        final int SRV_NODES = 3;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        final Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        final CacheEventListener3 lsnr = new CacheEventListener3();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(lsnr);

        IgniteCache<Object, Object> clnCache = qryClient.cache(null);

        QueryCursor<Cache.Entry<Object, Object>> query = clnCache.query(qry);

        Ignite igniteSrv = ignite(0);

        IgniteCache<Object, Object> srvCache = igniteSrv.cache(null);

        Affinity<Object> aff = affinity(srvCache);

        List<Integer> keys = testKeys(srvCache, 1);

        Collection<ClusterNode> nodes = aff.mapPartitionToPrimaryAndBackups(keys.get(0));

        Collection<UUID> ids = F.transform(nodes, new C1<ClusterNode, UUID>() {
            @Override public UUID apply(ClusterNode node) {
                return node.id();
            }
        });

        int keyIter = 0;

        boolean filtered = false;

        Map<Object, T2<Object, Object>> updates = new HashMap<>();

        final List<T3<Object, Object, Object>> expEvts = new ArrayList<>();

        for (; keyIter < keys.size() / 2; keyIter++) {
            int key = keys.get(keyIter);

            log.info("Put [key=" + key + ", part=" + aff.partition(key)
                + ", filtered=" + filtered + ']');

            T2<Object, Object> t = updates.get(key);

            Integer val = filtered ?
                (key % 2 == 0 ? key + 1 : key) :
                key * 2;

            if (t == null) {
                updates.put(key, new T2<>((Object)val, null));

                if (!filtered)
                    expEvts.add(new T3<>((Object)key, (Object)val, null));
            }
            else {
                updates.put(key, new T2<>((Object)val, (Object)key));

                if (!filtered)
                    expEvts.add(new T3<>((Object)key, (Object)val, (Object)key));
            }

            srvCache.put(key, val);

            filtered = !filtered;
        }

        checkEvents(expEvts, lsnr, false);

        List<Thread> stopThreads = new ArrayList<>(3);

        // Stop nodes which owning this partition.
        for (int i = 0; i < SRV_NODES; i++) {
            Ignite ignite = ignite(i);

            if (ids.contains(ignite.cluster().localNode().id())) {
                final int i0 = i;

                TestCommunicationSpi spi = (TestCommunicationSpi)ignite.configuration().getCommunicationSpi();

                spi.skipAllMsg = true;

                stopThreads.add(new Thread() {
                    @Override public void run() {
                        stopGrid(i0, true);
                    }
                });
            }
        }

        // Stop and join threads.
        for (Thread t : stopThreads)
            t.start();

        for (Thread t : stopThreads)
            t.join();

        assert GridTestUtils.waitForCondition(new PA() {
            @Override public boolean apply() {
                // (SRV_NODES + 1 client node) - 1 primary - backup nodes.
                return qryClient.cluster().nodes().size() == (SRV_NODES + 1 /** client node */)
                    - 1 /** Primary node */ - backups;
            }
        }, 5000L);

        for (; keyIter < keys.size(); keyIter++) {
            int key = keys.get(keyIter);

            log.info("Put [key=" + key + ", filtered=" + filtered + ']');

            T2<Object, Object> t = updates.get(key);

            Integer val = filtered ?
                (key % 2 == 0 ? key + 1 : key) :
                key * 2;

            if (t == null) {
                updates.put(key, new T2<>((Object)val, null));

                if (!filtered)
                    expEvts.add(new T3<>((Object)key, (Object)val, null));
            }
            else {
                updates.put(key, new T2<>((Object)val, (Object)key));

                if (!filtered)
                    expEvts.add(new T3<>((Object)key, (Object)val, (Object)key));
            }

            clnCache.put(key, val);

            filtered = !filtered;
        }

        checkEvents(expEvts, lsnr, false);

        query.close();
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemoteFilter() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClientCache = qryClient.cache(null);

        if (cacheMode() != REPLICATED)
            assertEquals(backups, qryClientCache.getConfiguration(CacheConfiguration.class).getBackups());

        Affinity<Object> aff = qryClient.affinity(null);

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        final CacheEventListener3 lsnr = new CacheEventListener3();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(lsnr);

        int PARTS = 10;

        QueryCursor<?> cur = qryClientCache.query(qry);

        Map<Object, T2<Object, Object>> updates = new HashMap<>();

        final List<T3<Object, Object, Object>> expEvts = new ArrayList<>();

        for (int i = 0; i < (atomicityMode() == CacheAtomicityMode.ATOMIC ? SRV_NODES - 1 : SRV_NODES - 2); i++) {
            log.info("Stop iteration: " + i);

            TestCommunicationSpi spi = (TestCommunicationSpi)ignite(i).configuration().getCommunicationSpi();

            Ignite ignite = ignite(i);

            IgniteCache<Object, Object> cache = ignite.cache(null);

            List<Integer> keys = testKeys(cache, PARTS);

            boolean first = true;

            boolean filtered = false;

            for (Integer key : keys) {
                log.info("Put [node=" + ignite.name() + ", key=" + key + ", part=" + aff.partition(key)
                    + ", filtered=" + filtered + ']');

                T2<Object, Object> t = updates.get(key);

                Integer val = filtered ?
                    (key % 2 == 0 ? key + 1 : key) :
                    key * 2;

                if (t == null) {
                    updates.put(key, new T2<>((Object)val, null));

                    if (!filtered)
                        expEvts.add(new T3<>((Object)key, (Object)val, null));
                }
                else {
                    updates.put(key, new T2<>((Object)val, (Object)key));

                    if (!filtered)
                        expEvts.add(new T3<>((Object)key, (Object)val, (Object)key));
                }

                cache.put(key, val);

                if (first) {
                    spi.skipMsg = true;

                    first = false;
                }

                filtered = !filtered;
            }

            stopGrid(i);

            boolean check = GridTestUtils.waitForCondition(new PAX() {
                @Override public boolean applyx() throws IgniteCheckedException {
                    return expEvts.size() == lsnr.keys.size();
                }
            }, 5000L);

            if (!check) {
                Set<Integer> keys0 = new HashSet<>(keys);

                keys0.removeAll(lsnr.keys);

                log.info("Missed events for keys: " + keys0);

                fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + keys0.size() + ']');
            }

            checkEvents(expEvts, lsnr, false);
        }

        cur.close();
    }

    /**
     * @throws Exception If failed.
     */
    public void testThreeBackups() throws Exception {
        if (cacheMode() == REPLICATED)
            return;

        checkBackupQueue(3, false);
    }

    /** {@inheritDoc} */
    @Override public boolean isDebug() {
        return true;
    }

    /**
     * @param backups Number of backups.
     * @param updateFromClient If {@code true} executes cache update from client node.
     * @throws Exception If failed.
     */
    private void checkBackupQueue(int backups, boolean updateFromClient) throws Exception {
        this.backups = atomicityMode() == CacheAtomicityMode.ATOMIC ? backups :
            backups < 2 ? 2 : backups;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClientCache = qryClient.cache(null);

        Affinity<Object> aff = qryClient.affinity(null);

        CacheEventListener1 lsnr = new CacheEventListener1(false);

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        QueryCursor<?> cur = qryClientCache.query(qry);

        int PARTS = 10;

        Map<Object, T2<Object, Object>> updates = new HashMap<>();

        List<T3<Object, Object, Object>> expEvts = new ArrayList<>();

        for (int i = 0; i < (atomicityMode() == CacheAtomicityMode.ATOMIC ? SRV_NODES - 1 : SRV_NODES - 2); i++) {
            log.info("Stop iteration: " + i);

            TestCommunicationSpi spi = (TestCommunicationSpi)ignite(i).configuration().getCommunicationSpi();

            Ignite ignite = ignite(i);

            IgniteCache<Object, Object> cache = ignite.cache(null);

            List<Integer> keys = testKeys(cache, PARTS);

            CountDownLatch latch = new CountDownLatch(keys.size());

            lsnr.latch = latch;

            boolean first = true;

            for (Integer key : keys) {
                log.info("Put [node=" + ignite.name() + ", key=" + key + ", part=" + aff.partition(key) + ']');

                T2<Object, Object> t = updates.get(key);

                if (updateFromClient) {
                    if (atomicityMode() == CacheAtomicityMode.TRANSACTIONAL) {
                        try (Transaction tx = qryClient.transactions().txStart()) {
                            qryClientCache.put(key, key);

                            tx.commit();
                        }
                        catch (CacheException | ClusterTopologyException e) {
                            log.warning("Failed put. [Key=" + key + ", val=" + key + "]");

                            continue;
                        }
                    }
                    else
                        qryClientCache.put(key, key);
                }
                else {
                    if (atomicityMode() == CacheAtomicityMode.TRANSACTIONAL) {
                        try (Transaction tx = ignite.transactions().txStart()) {
                            cache.put(key, key);

                            tx.commit();
                        }
                        catch (CacheException | ClusterTopologyException e) {
                            log.warning("Failed put. [Key=" + key + ", val=" + key + "]");

                            continue;
                        }
                    }
                    else
                        cache.put(key, key);
                }

                if (t == null) {
                    updates.put(key, new T2<>((Object)key, null));

                    expEvts.add(new T3<>((Object)key, (Object)key, null));
                }
                else {
                    updates.put(key, new T2<>((Object)key, (Object)key));

                    expEvts.add(new T3<>((Object)key, (Object)key, (Object)key));
                }

                if (first) {
                    spi.skipMsg = true;

                    first = false;
                }
            }

            stopGrid(i);

            if (!latch.await(5, SECONDS)) {
                Set<Integer> keys0 = new HashSet<>(keys);

                keys0.removeAll(lsnr.keys);

                log.info("Missed events for keys: " + keys0);

                fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + lsnr.latch.getCount() + ']');
            }

            checkEvents(expEvts, lsnr);
        }

        for (int i = 0; i < (atomicityMode() == CacheAtomicityMode.ATOMIC ? SRV_NODES - 1 : SRV_NODES - 2); i++) {
            log.info("Start iteration: " + i);

            Ignite ignite = startGrid(i);

            IgniteCache<Object, Object> cache = ignite.cache(null);

            List<Integer> keys = testKeys(cache, PARTS);

            CountDownLatch latch = new CountDownLatch(keys.size());

            lsnr.latch = latch;

            for (Integer key : keys) {
                log.info("Put [node=" + ignite.name() + ", key=" + key + ", part=" + aff.partition(key) + ']');

                T2<Object, Object> t = updates.get(key);

                if (t == null) {
                    updates.put(key, new T2<>((Object)key, null));

                    expEvts.add(new T3<>((Object)key, (Object)key, null));
                }
                else {
                    updates.put(key, new T2<>((Object)key, (Object)key));

                    expEvts.add(new T3<>((Object)key, (Object)key, (Object)key));
                }

                if (updateFromClient)
                    qryClientCache.put(key, key);
                else
                    cache.put(key, key);
            }

            if (!latch.await(10, SECONDS)) {
                Set<Integer> keys0 = new HashSet<>(keys);

                keys0.removeAll(lsnr.keys);

                log.info("Missed events for keys: " + keys0);

                fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + lsnr.latch.getCount() + ']');
            }

            checkEvents(expEvts, lsnr);
        }

        cur.close();

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @param expEvts Expected events.
     * @param lsnr Listener.
     */
    private void checkEvents(List<T3<Object, Object, Object>> expEvts, CacheEventListener1 lsnr) {
        for (T3<Object, Object, Object> exp : expEvts) {
            CacheEntryEvent<?, ?> e = lsnr.evts.get(exp.get1());

            assertNotNull("No event for key: " + exp.get1(), e);
            assertEquals("Unexpected value: " + e, exp.get2(), e.getValue());
        }

        expEvts.clear();

        lsnr.evts.clear();
    }

    /**
     * @param expEvts Expected events.
     * @param lsnr Listener.
     * @param lostAllow If {@code true} than won't assert on lost events.
     */
    private void checkEvents(final List<T3<Object, Object, Object>> expEvts, final CacheEventListener2 lsnr,
        boolean lostAllow) throws Exception {
        GridTestUtils.waitForCondition(new PA() {
            @Override public boolean apply() {
                return expEvts.size() == lsnr.size();
            }
        }, 2000L);

        Map<Integer, List<CacheEntryEvent<?, ?>>> prevMap = new HashMap<>(lsnr.evts.size());

        for (Map.Entry<Integer, List<CacheEntryEvent<?, ?>>> e : lsnr.evts.entrySet())
            prevMap.put(e.getKey(), new ArrayList<>(e.getValue()));

        List<T3<Object, Object, Object>> lostEvents = new ArrayList<>();

        for (T3<Object, Object, Object> exp : expEvts) {
            List<CacheEntryEvent<?, ?>> rcvdEvts = lsnr.evts.get(exp.get1());

            if (F.eq(exp.get2(), exp.get3()))
                continue;

            if (rcvdEvts == null || rcvdEvts.isEmpty()) {
                lostEvents.add(exp);

                continue;
            }

            Iterator<CacheEntryEvent<?, ?>> iter = rcvdEvts.iterator();

            boolean found = false;

            while (iter.hasNext()) {
                CacheEntryEvent<?, ?> e = iter.next();

                if ((exp.get2() != null && e.getValue() != null && exp.get2().equals(e.getValue()))
                    && equalOldValue(e, exp)) {
                    found = true;

                    iter.remove();

                    break;
                }
            }

            // Lost event is acceptable.
            if (!found)
                lostEvents.add(exp);
        }

        boolean dup = false;

        // Check duplicate.
        if (!lsnr.evts.isEmpty()) {
            for (List<CacheEntryEvent<?, ?>> evts : lsnr.evts.values()) {
                if (!evts.isEmpty()) {
                    for (CacheEntryEvent<?, ?> e : evts) {
                        boolean found = false;

                        for (T3<Object, Object, Object> lostEvt : lostEvents) {
                            if (e.getKey().equals(lostEvt.get1()) && e.getValue().equals(lostEvt.get2())) {
                                found = true;

                                lostEvents.remove(lostEvt);

                                break;
                            }
                        }

                        if (!found) {
                            dup = true;

                            break;
                        }
                    }
                }
            }

            if (dup) {
                for (T3<Object, Object, Object> e : lostEvents)
                    log.error("Lost event: " + e);

                for (List<CacheEntryEvent<?, ?>> e : lsnr.evts.values()) {
                    if (!e.isEmpty()) {
                        for (CacheEntryEvent<?, ?> event : e) {
                            List<CacheEntryEvent<?, ?>> entries = new ArrayList<>();

                            for (CacheEntryEvent<?, ?> ev0 : prevMap.get(event.getKey())) {
                                if (F.eq(event.getValue(), ev0.getValue()) && F.eq(event.getOldValue(),
                                    ev0.getOldValue()))
                                    entries.add(ev0);
                            }
                        }
                    }
                }
            }
        }

        if (!lostAllow && !lostEvents.isEmpty()) {
            log.error("Lost event cnt: " + lostEvents.size());

            for (T3<Object, Object, Object> e : lostEvents)
                log.error("Lost event: " + e);

            fail("Lose events, see log for details.");
        }

        log.error("Lost event cnt: " + lostEvents.size());

        expEvts.clear();

        lsnr.evts.clear();
        lsnr.vals.clear();
    }

    /**
     * @param e Event
     * @param expVals expected value
     * @return {@code True} if entries has the same key, value and oldValue. If cache start without backups
     *          than oldValue ignoring in comparison.
     */
    private boolean equalOldValue(CacheEntryEvent<?, ?> e, T3<Object, Object, Object> expVals) {
        return (e.getOldValue() == null && expVals.get3() == null) // Both null
            || (e.getOldValue() != null && expVals.get3() != null  // Equals
                && e.getOldValue().equals(expVals.get3()))
            || (backups == 0); // If we start without backup than oldValue might be lose.
    }

    /**
     * @param expEvts Expected events.
     * @param lsnr Listener.
     */
    private void checkEvents(final List<T3<Object, Object, Object>> expEvts, final CacheEventListener3 lsnr,
        boolean allowLoseEvent) throws Exception {
        if (!allowLoseEvent)
            assert GridTestUtils.waitForCondition(new PA() {
                @Override public boolean apply() {
                    return lsnr.evts.size() == expEvts.size();
                }
            }, 2000L);

        for (T3<Object, Object, Object> exp : expEvts) {
            CacheEntryEvent<?, ?> e = lsnr.evts.get(exp.get1());

            assertNotNull("No event for key: " + exp.get1(), e);
            assertEquals("Unexpected value: " + e, exp.get2(), e.getValue());

            if (allowLoseEvent)
                lsnr.evts.remove(exp.get1());
        }

        if (allowLoseEvent)
            assert lsnr.evts.isEmpty();

        expEvts.clear();

        lsnr.evts.clear();
        lsnr.keys.clear();
    }

    /**
     * @param cache Cache.
     * @param parts Number of partitions.
     * @return Keys.
     */
    private List<Integer> testKeys(IgniteCache<Object, Object> cache, int parts) {
        Ignite ignite = cache.unwrap(Ignite.class);

        List<Integer> res = new ArrayList<>();

        Affinity<Object> aff = ignite.affinity(cache.getName());

        ClusterNode node = ignite.cluster().localNode();

        int[] nodeParts = aff.primaryPartitions(node);

        final int KEYS_PER_PART = 50;

        for (int i = 0; i < parts; i++) {
            int part = nodeParts[i];

            int cnt = 0;

            for (int key = 0; key < 100_000; key++) {
                if (aff.partition(key) == part && aff.isPrimary(node, key)) {
                    res.add(key);

                    if (++cnt == KEYS_PER_PART)
                        break;
                }
            }

            assertEquals(KEYS_PER_PART, cnt);
        }

        assertEquals(parts * KEYS_PER_PART, res.size());

        return res;
    }

    /**
     * @throws Exception If failed.
     */
    public void testBackupQueueCleanupClientQuery() throws Exception {
        startGridsMultiThreaded(2);

        client = true;

        Ignite qryClient = startGrid(2);

        CacheEventListener1 lsnr = new CacheEventListener1(false);

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        QueryCursor<?> cur = qryClient.cache(null).query(qry);

        final Collection<Object> backupQueue = backupQueue(ignite(1));

        assertEquals(0, backupQueue.size());

        IgniteCache<Object, Object> cache0 = ignite(0).cache(null);

        List<Integer> keys = primaryKeys(cache0, BACKUP_ACK_THRESHOLD);

        CountDownLatch latch = new CountDownLatch(keys.size());

        lsnr.latch = latch;

        for (Integer key : keys) {
            log.info("Put: " + key);

            cache0.put(key, key);
        }

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return backupQueue.isEmpty();
            }
        }, 2000);

        assertTrue("Backup queue is not cleared: " + backupQueue, backupQueue.size() < BACKUP_ACK_THRESHOLD);

        if (!latch.await(5, SECONDS))
            fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + lsnr.latch.getCount() + ']');

        keys = primaryKeys(cache0, BACKUP_ACK_THRESHOLD / 2);

        latch = new CountDownLatch(keys.size());

        lsnr.latch = latch;

        for (Integer key : keys)
            cache0.put(key, key);

        final long ACK_FREQ = 5000;

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return backupQueue.isEmpty();
            }
        }, ACK_FREQ + 2000);

        assertTrue("Backup queue is not cleared: " + backupQueue, backupQueue.isEmpty());

        if (!latch.await(5, SECONDS))
            fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + lsnr.latch.getCount() + ']');

        cur.close();

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @throws Exception If failed.
     */
    public void testBackupQueueCleanupServerQuery() throws Exception {
        Ignite qryClient = startGridsMultiThreaded(2);

        CacheEventListener1 lsnr = new CacheEventListener1(false);

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        IgniteCache<Object, Object> cache = qryClient.cache(null);

        QueryCursor<?> cur = cache.query(qry);

        final Collection<Object> backupQueue = backupQueue(ignite(1));

        assertEquals(0, backupQueue.size());

        List<Integer> keys = primaryKeys(cache, BACKUP_ACK_THRESHOLD);

        CountDownLatch latch = new CountDownLatch(keys.size());

        lsnr.latch = latch;

        for (Integer key : keys) {
            log.info("Put: " + key);

            cache.put(key, key);
        }

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return backupQueue.isEmpty();
            }
        }, 3000);

        assertTrue("Backup queue is not cleared: " + backupQueue, backupQueue.size() < BACKUP_ACK_THRESHOLD);

        if (!latch.await(5, SECONDS))
            fail("Failed to wait for notifications [exp=" + keys.size() + ", left=" + lsnr.latch.getCount() + ']');

        cur.close();
    }

    /**
     * @param ignite Ignite.
     * @return Backup queue for test query.
     */
    private Collection<Object> backupQueue(Ignite ignite) {
        GridContinuousProcessor proc = ((IgniteKernal)ignite).context().continuous();

        ConcurrentMap<Object, Object> infos = GridTestUtils.getFieldValue(proc, "rmtInfos");

        Collection<Object> backupQueue = null;

        for (Object info : infos.values()) {
            GridContinuousHandler hnd = GridTestUtils.getFieldValue(info, "hnd");

            if (hnd.isForQuery() && hnd.cacheName() == null) {
                backupQueue = GridTestUtils.getFieldValue(hnd, "backupQueue");

                break;
            }
        }

        assertNotNull(backupQueue);

        return backupQueue;
    }

    /**
     * @throws Exception If failed.
     */
    public void testFailover() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        final Ignite qryCln = startGrid(SRV_NODES);

        client = false;

        final IgniteCache<Object, Object> qryClnCache = qryCln.cache(null);

        final CacheEventListener2 lsnr = new CacheEventListener2();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        QueryCursor<?> cur = qryClnCache.query(qry);

        final AtomicBoolean stop = new AtomicBoolean();

        final AtomicReference<CountDownLatch> checkLatch = new AtomicReference<>();

        boolean processorPut = false;

        IgniteInternalFuture<?> restartFut = GridTestUtils.runAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                final int idx = SRV_NODES + 1;

                while (!stop.get() && !err) {
                    log.info("Start node: " + idx);

                    startGrid(idx);

                    Thread.sleep(200);

                    log.info("Stop node: " + idx);

                    try {
                        stopGrid(idx);
                    }
                    catch (Exception e) {
                        log.warning("Failed to stop nodes.", e);
                    }

                    CountDownLatch latch = new CountDownLatch(1);

                    assertTrue(checkLatch.compareAndSet(null, latch));

                    if (!stop.get()) {
                        log.info("Wait for event check.");

                        assertTrue(latch.await(1, MINUTES));
                    }
                }

                return null;
            }
        });

        final Map<Integer, Integer> vals = new HashMap<>();

        final Map<Integer, List<T2<Integer, Integer>>> expEvts = new HashMap<>();

        try {
            long stopTime = System.currentTimeMillis() + 60_000;

            final int PARTS = qryCln.affinity(null).partitions();

            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            while (System.currentTimeMillis() < stopTime) {
                Integer key = rnd.nextInt(PARTS);

                Integer prevVal = vals.get(key);
                Integer val = vals.get(key);

                if (val == null)
                    val = 0;
                else
                    val = val + 1;

                if (processorPut && prevVal != null) {
                    if (atomicityMode() == CacheAtomicityMode.TRANSACTIONAL) {
                        try (Transaction tx = qryCln.transactions().txStart()) {
                            qryClnCache.invoke(key, new CacheEntryProcessor<Object, Object, Void>() {
                                @Override public Void process(MutableEntry<Object, Object> e,
                                    Object... arg) throws EntryProcessorException {
                                    e.setValue(arg[0]);

                                    return null;
                                }
                            }, val);

                            tx.commit();
                        }
                        catch (CacheException | ClusterTopologyException e) {
                            log.warning("Failed put. [Key=" + key + ", val=" + val + "]");

                            continue;
                        }
                    }
                    else
                        qryClnCache.invoke(key, new CacheEntryProcessor<Object, Object, Void>() {
                            @Override public Void process(MutableEntry<Object, Object> e,
                                Object... arg) throws EntryProcessorException {
                                e.setValue(arg[0]);

                                return null;
                            }
                        }, val);
                }
                else {
                    if (atomicityMode() == CacheAtomicityMode.TRANSACTIONAL) {
                        try (Transaction tx = qryCln.transactions().txStart()) {
                            qryClnCache.put(key, val);

                            tx.commit();
                        }
                        catch (CacheException | ClusterTopologyException e) {
                            log.warning("Failed put. [Key=" + key + ", val=" + val + "]");

                            continue;
                        }
                    }
                    else
                        qryClnCache.put(key, val);
                }

                processorPut = !processorPut;

                vals.put(key, val);

                List<T2<Integer, Integer>> keyEvts = expEvts.get(key);

                if (keyEvts == null) {
                    keyEvts = new ArrayList<>();

                    expEvts.put(key, keyEvts);
                }

                keyEvts.add(new T2<>(val, prevVal));

                CountDownLatch latch = checkLatch.get();

                if (latch != null) {
                    log.info("Check events.");

                    checkLatch.set(null);

                    boolean success = false;

                    try {
                        if (err)
                            break;

                        boolean check = GridTestUtils.waitForCondition(new GridAbsPredicate() {
                            @Override public boolean apply() {
                                return checkEvents(false, expEvts, lsnr);
                            }
                        }, 10_000);

                        if (!check)
                            assertTrue(checkEvents(true, expEvts, lsnr));

                        success = true;

                        log.info("Events checked.");
                    }
                    finally {
                        if (!success)
                            err = true;

                        latch.countDown();
                    }
                }
            }
        }
        finally {
            stop.set(true);
        }

        CountDownLatch latch = checkLatch.get();

        if (latch != null)
            latch.countDown();

        restartFut.get();

        boolean check = true;

        if (!expEvts.isEmpty())
            check = GridTestUtils.waitForCondition(new GridAbsPredicate() {
                @Override public boolean apply() {
                    return checkEvents(false, expEvts, lsnr);
                }
            }, 10_000);

        if (!check)
            assertTrue(checkEvents(true, expEvts, lsnr));

        cur.close();

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @throws Exception If failed.
     */
    public void testFailoverFilter() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClientCache = qryClient.cache(null);

        final CacheEventListener2 lsnr = new CacheEventListener2();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(new CacheEventFilter());

        QueryCursor<?> cur = qryClientCache.query(qry);

        final AtomicBoolean stop = new AtomicBoolean();

        final AtomicReference<CountDownLatch> checkLatch = new AtomicReference<>();

        IgniteInternalFuture<?> restartFut = GridTestUtils.runAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                final int idx = SRV_NODES + 1;

                while (!stop.get() && !err) {
                    log.info("Start node: " + idx);

                    startGrid(idx);

                    Thread.sleep(200);

                    log.info("Stop node: " + idx);

                    stopGrid(idx);

                    CountDownLatch latch = new CountDownLatch(1);

                    assertTrue(checkLatch.compareAndSet(null, latch));

                    if (!stop.get()) {
                        log.info("Wait for event check.");

                        assertTrue(latch.await(1, MINUTES));
                    }
                }

                return null;
            }
        });

        final Map<Integer, Integer> vals = new HashMap<>();

        final Map<Integer, List<T2<Integer, Integer>>> expEvts = new HashMap<>();

        try {
            long stopTime = System.currentTimeMillis() + 60_000;

            final int PARTS = qryClient.affinity(null).partitions();

            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            boolean filtered = false;

            boolean processorPut = false;

            while (System.currentTimeMillis() < stopTime) {
                Integer key = rnd.nextInt(PARTS);

                Integer prevVal = vals.get(key);
                Integer val = vals.get(key);

                if (val == null)
                    val = 0;
                else
                    val = Math.abs(val) + 1;

                if (filtered)
                    val = -val;

                if (processorPut && prevVal != null) {
                    qryClientCache.invoke(key, new CacheEntryProcessor<Object, Object, Void>() {
                        @Override public Void process(MutableEntry<Object, Object> entry,
                            Object... arguments) throws EntryProcessorException {
                            entry.setValue(arguments[0]);

                            return null;
                        }
                    }, val);
                }
                else
                    qryClientCache.put(key, val);

                processorPut = !processorPut;

                vals.put(key, val);

                if (val >= 0) {
                    List<T2<Integer, Integer>> keyEvts = expEvts.get(key);

                    if (keyEvts == null) {
                        keyEvts = new ArrayList<>();

                        expEvts.put(key, keyEvts);
                    }

                    keyEvts.add(new T2<>(val, prevVal));
                }

                filtered = !filtered;

                CountDownLatch latch = checkLatch.get();

                if (latch != null) {
                    log.info("Check events.");

                    checkLatch.set(null);

                    boolean success = false;

                    try {
                        if (err)
                            break;

                        boolean check = GridTestUtils.waitForCondition(new GridAbsPredicate() {
                            @Override public boolean apply() {
                                return checkEvents(false, expEvts, lsnr);
                            }
                        }, 10_000);

                        if (!check)
                            assertTrue(checkEvents(true, expEvts, lsnr));

                        success = true;

                        log.info("Events checked.");
                    }
                    finally {
                        if (!success)
                            err = true;

                        latch.countDown();
                    }
                }
            }
        }
        finally {
            stop.set(true);
        }

        CountDownLatch latch = checkLatch.get();

        if (latch != null)
            latch.countDown();

        restartFut.get();

        boolean check = true;

        if (!expEvts.isEmpty()) {
            check = GridTestUtils.waitForCondition(new GridAbsPredicate() {
                @Override public boolean apply() {
                    return checkEvents(false, expEvts, lsnr);
                }
            }, 10_000);
        }

        if (!check)
            assertTrue(checkEvents(true, expEvts, lsnr));

        cur.close();

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @throws Exception If failed.
     */
    public void testFailoverStartStopBackup() throws Exception {
        failoverStartStopFilter(2);
    }

    /**
     * @throws Exception If failed.
     */
    public void testStartStop() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClnCache = qryClient.cache(null);

        Affinity<Object> aff = qryClient.affinity(null);

        final CacheEventListener2 lsnr = new CacheEventListener2();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(new CacheEventFilter());

        QueryCursor<?> cur = qryClnCache.query(qry);

        for (int i = 0; i < 10; i++) {
            final int idx = i % (SRV_NODES - 1);

            log.info("Stop node: " + idx);

            stopGrid(idx);

            awaitPartitionMapExchange();

            List<T3<Object, Object, Object>> afterRestEvents = new ArrayList<>();

            for (int j = 0; j < aff.partitions(); j++) {
                Integer oldVal = (Integer)qryClnCache.get(j);

                qryClnCache.put(j, i);

                afterRestEvents.add(new T3<>((Object)j, (Object)i, (Object)oldVal));
            }

            checkEvents(new ArrayList<>(afterRestEvents), lsnr, false);

            log.info("Start node: " + idx);

            startGrid(idx);
        }

        cur.close();
    }

    /**
     * @throws Exception If failed.
     */
    public void failoverStartStopFilter(int backups) throws Exception {
        this.backups = backups;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        client = false;

        IgniteCache<Object, Object> qryClnCache = qryClient.cache(null);

        final CacheEventListener2 lsnr = new CacheEventListener2();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        qry.setRemoteFilter(new CacheEventFilter());

        QueryCursor<?> cur = qryClnCache.query(qry);

        CacheEventListener2 dinLsnr = null;

        QueryCursor<?> dinQry = null;

        final AtomicBoolean stop = new AtomicBoolean();

        final AtomicReference<CountDownLatch> checkLatch = new AtomicReference<>();

        IgniteInternalFuture<?> restartFut = GridTestUtils.runAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                while (!stop.get() && !err) {
                    final int idx = ThreadLocalRandom.current().nextInt(SRV_NODES - 1);

                    log.info("Stop node: " + idx);

                    awaitPartitionMapExchange();

                    Thread.sleep(400);

                    stopGrid(idx);

                    awaitPartitionMapExchange();

                    Thread.sleep(400);

                    log.info("Start node: " + idx);

                    startGrid(idx);

                    Thread.sleep(200);

                    CountDownLatch latch = new CountDownLatch(1);

                    assertTrue(checkLatch.compareAndSet(null, latch));

                    if (!stop.get()) {
                        log.info("Wait for event check.");

                        assertTrue(latch.await(1, MINUTES));
                    }
                }

                return null;
            }
        });

        final Map<Integer, Integer> vals = new HashMap<>();

        final Map<Integer, List<T2<Integer, Integer>>> expEvts = new HashMap<>();

        final List<T3<Object, Object, Object>> expEvtsNewLsnr = new ArrayList<>();

        final List<T3<Object, Object, Object>> expEvtsLsnr = new ArrayList<>();

        try {
            long stopTime = System.currentTimeMillis() + 60_000;

            // Start new filter each 5 sec.
            long startFilterTime = System.currentTimeMillis() + 5_000;

            final int PARTS = qryClient.affinity(null).partitions();

            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            boolean filtered = false;

            boolean processorPut = false;

            while (System.currentTimeMillis() < stopTime) {
                Integer key = rnd.nextInt(PARTS);

                Integer prevVal = vals.get(key);
                Integer val = vals.get(key);

                if (System.currentTimeMillis() > startFilterTime) {
                    // Stop filter and check events.
                    if (dinQry != null) {
                        dinQry.close();

                        log.info("Continuous query listener closed. Await events: " + expEvtsNewLsnr.size());

                        checkEvents(expEvtsNewLsnr, dinLsnr, backups == 0);
                    }

                    dinLsnr = new CacheEventListener2();

                    ContinuousQuery<Object, Object> newQry = new ContinuousQuery<>();

                    newQry.setLocalListener(dinLsnr);

                    newQry.setRemoteFilter(new CacheEventFilter());

                    dinQry = qryClnCache.query(newQry);

                    log.info("Continuous query listener started.");

                    startFilterTime = System.currentTimeMillis() + 5_000;
                }

                if (val == null)
                    val = 0;
                else
                    val = Math.abs(val) + 1;

                if (filtered)
                    val = -val;

                if (processorPut && prevVal != null) {
                    qryClnCache.invoke(key, new CacheEntryProcessor<Object, Object, Void>() {
                        @Override public Void process(MutableEntry<Object, Object> entry,
                            Object... arguments) throws EntryProcessorException {
                            entry.setValue(arguments[0]);

                            return null;
                        }
                    }, val);
                }
                else
                    qryClnCache.put(key, val);

                processorPut = !processorPut;

                vals.put(key, val);

                if (val >= 0) {
                    List<T2<Integer, Integer>> keyEvts = expEvts.get(key);

                    if (keyEvts == null) {
                        keyEvts = new ArrayList<>();

                        expEvts.put(key, keyEvts);
                    }

                    keyEvts.add(new T2<>(val, prevVal));

                    T3<Object, Object, Object> tupVal = new T3<>((Object)key, (Object)val, (Object)prevVal);

                    expEvtsLsnr.add(tupVal);

                    if (dinQry != null)
                        expEvtsNewLsnr.add(tupVal);
                }

                filtered = !filtered;

                CountDownLatch latch = checkLatch.get();

                if (latch != null) {
                    log.info("Check events.");

                    checkLatch.set(null);

                    boolean success = false;

                    try {
                        if (err)
                            break;

                        checkEvents(expEvtsLsnr, lsnr, backups == 0);

                        success = true;

                        log.info("Events checked.");
                    }
                    finally {
                        if (!success)
                            err = true;

                        latch.countDown();
                    }
                }
            }
        }
        finally {
            stop.set(true);
        }

        CountDownLatch latch = checkLatch.get();

        if (latch != null)
            latch.countDown();

        restartFut.get();

        checkEvents(expEvtsLsnr, lsnr, backups == 0);

        lsnr.evts.clear();
        lsnr.vals.clear();

        if (dinQry != null) {
            checkEvents(expEvtsNewLsnr, dinLsnr, backups == 0);

            dinLsnr.evts.clear();
            dinLsnr.vals.clear();
        }

        List<T3<Object, Object, Object>> afterRestEvents = new ArrayList<>();

        for (int i = 0; i < qryClient.affinity(null).partitions(); i++) {
            Integer oldVal = (Integer)qryClnCache.get(i);

            qryClnCache.put(i, i);

            afterRestEvents.add(new T3<>((Object)i, (Object)i, (Object)oldVal));
        }

        checkEvents(new ArrayList<>(afterRestEvents), lsnr, false);

        cur.close();

        if (dinQry != null) {
            checkEvents(new ArrayList<>(afterRestEvents), dinLsnr, false);

            dinQry.close();
        }

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultiThreadedFailover() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 4;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        final Ignite qryCln = startGrid(SRV_NODES);

        client = false;

        final IgniteCache<Object, Object> qryClnCache = qryCln.cache(null);

        final CacheEventListener2 lsnr = new CacheEventListener2();

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        QueryCursor<?> cur = qryClnCache.query(qry);

        final AtomicBoolean stop = new AtomicBoolean();

        final int THREAD = 4;

        final int PARTS = THREAD;

        final List<List<T3<Object, Object, Object>>> expEvts = new ArrayList<>(THREAD + 5);

        for (int i = 0; i < THREAD; i++)
            expEvts.add(i, new ArrayList<T3<Object, Object, Object>>());

        final AtomicReference<CyclicBarrier> checkBarrier = new AtomicReference<>();

        IgniteInternalFuture<?> restartFut = GridTestUtils.runAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                final int idx = SRV_NODES + 1;

                while (!stop.get() && !err) {
                    log.info("Start node: " + idx);

                    startGrid(idx);

                    awaitPartitionMapExchange();

                    Thread.sleep(100);

                    try {
                        log.info("Stop node: " + idx);

                        stopGrid(idx);

                        awaitPartitionMapExchange();

                        Thread.sleep(100);
                    }
                    catch (Exception e) {
                        log.warning("Failed to stop nodes.", e);
                    }

                    CyclicBarrier bar = new CyclicBarrier(THREAD + 1 /* plus start/stop thread */, new Runnable() {
                        @Override public void run() {
                            try {
                                GridTestUtils.waitForCondition(new PA() {
                                    @Override public boolean apply() {
                                        int size = 0;

                                        for (List<T3<Object, Object, Object>> evt : expEvts)
                                            size += evt.size();

                                        return lsnr.size() <= size;
                                    }
                                }, 2000L);

                                List<T3<Object, Object, Object>> expEvts0 = new ArrayList<>();

                                for (List<T3<Object, Object, Object>> evt : expEvts)
                                    expEvts0.addAll(evt);

                                checkEvents(expEvts0, lsnr, false);

                                for (List<T3<Object, Object, Object>> evt : expEvts)
                                    evt.clear();
                            }
                            catch (Exception e) {
                                log.error("Failed.", e);

                                err = true;

                                stop.set(true);
                            }
                            finally {
                                checkBarrier.set(null);
                            }
                        }
                    });

                    assertTrue(checkBarrier.compareAndSet(null, bar));

                    if (!stop.get() && !err)
                        bar.await(5, MINUTES);
                }

                return null;
            }
        });

        final long stopTime = System.currentTimeMillis() + 60_000;

        final AtomicInteger valCntr = new AtomicInteger(0);

        final AtomicInteger threadSeq = new AtomicInteger(0);

        GridTestUtils.runMultiThreaded(new Runnable() {
            @Override public void run() {
                try {
                    final ThreadLocalRandom rnd = ThreadLocalRandom.current();

                    final int threadId = threadSeq.getAndIncrement();

                    log.error("Thread id: " + threadId);

                    while (System.currentTimeMillis() < stopTime && !stop.get() && !err) {
                        Integer key = rnd.nextInt(PARTS);

                        Integer val = valCntr.incrementAndGet();

                        Integer prevVal = (Integer)qryClnCache.getAndPut(key, val);

                        expEvts.get(threadId).add(new T3<>((Object)key, (Object)val, (Object)prevVal));

                        CyclicBarrier bar = checkBarrier.get();

                        if (bar != null)
                            bar.await();
                    }
                }
                catch (Exception e){
                    log.error("Failed.", e);

                    err = true;

                    stop.set(true);
                }
                finally {
                    stop.set(true);
                }
            }
        }, THREAD, "update-thread");

        restartFut.get();

        List<T3<Object, Object, Object>> expEvts0 = new ArrayList<>();

        for (List<T3<Object, Object, Object>> evt : expEvts) {
            expEvts0.addAll(evt);

            evt.clear();
        }

        if (!expEvts0.isEmpty())
            checkEvents(expEvts0, lsnr, true);

        cur.close();

        assertFalse("Unexpected error during test, see log for details.", err);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultiThreaded() throws Exception {
        this.backups = 2;

        final int SRV_NODES = 3;

        startGridsMultiThreaded(SRV_NODES);

        client = true;

        Ignite qryClient = startGrid(SRV_NODES);

        final IgniteCache<Object, Object> cache = qryClient.cache(null);

        CacheEventListener1 lsnr = new CacheEventListener1(true);

        ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

        qry.setLocalListener(lsnr);

        QueryCursor<?> cur = cache.query(qry);

        client = false;

        final int SRV_IDX = SRV_NODES - 1;

        List<Integer> keys = primaryKeys(ignite(SRV_IDX).cache(null), 10);

        final int THREADS = 10;

        for (int i = 0; i < keys.size(); i++) {
            log.info("Iteration: " + i);

            Ignite srv = ignite(SRV_IDX);

            TestCommunicationSpi spi = (TestCommunicationSpi)srv.configuration().getCommunicationSpi();

            spi.sndFirstOnly = new AtomicBoolean(false);

            final Integer key = keys.get(i);

            final AtomicInteger val = new AtomicInteger();

            CountDownLatch latch = new CountDownLatch(THREADS);

            lsnr.latch = latch;

            IgniteInternalFuture<?> fut = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    Integer val0 = val.getAndIncrement();

                    cache.put(key, val0);

                    return null;
                }
            }, THREADS, "update-thread");

            fut.get();

            stopGrid(SRV_IDX);

            if (!latch.await(5, SECONDS))
                fail("Failed to wait for notifications [exp=" + THREADS + ", left=" + lsnr.latch.getCount() + ']');

            assertEquals(THREADS, lsnr.allEvts.size());

            Set<Integer> vals = new HashSet<>();

            boolean err = false;

            for (CacheEntryEvent<?, ?> evt : lsnr.allEvts) {
                assertEquals(key, evt.getKey());
                assertNotNull(evt.getValue());

                if (!vals.add((Integer)evt.getValue())) {
                    err = true;

                    log.info("Extra event: " + evt);
                }
            }

            for (int v = 0; v < THREADS; v++) {
                if (!vals.contains(v)) {
                    err = true;

                    log.info("Event for value not received: " + v);
                }
            }

            assertFalse("Invalid events, see log for details.", err);

            lsnr.allEvts.clear();

            startGrid(SRV_IDX);
        }

        cur.close();
    }

    /**
     * @param logAll If {@code true} logs all unexpected values.
     * @param expEvts Expected values.
     * @param lsnr Listener.
     * @return Check status.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private boolean checkEvents(boolean logAll,
        Map<Integer, List<T2<Integer, Integer>>> expEvts,
        CacheEventListener2 lsnr) {
        assertTrue(!expEvts.isEmpty());

        boolean pass = true;

        for (Map.Entry<Integer, List<T2<Integer, Integer>>> e : expEvts.entrySet()) {
            Integer key = e.getKey();
            List<T2<Integer, Integer>> exp = e.getValue();

            List<CacheEntryEvent<?, ?>> rcvdEvts = lsnr.evts.get(key);

            if (rcvdEvts == null) {
                pass = false;

                log.info("No events for key [key=" + key + ", exp=" + e.getValue() + ']');

                if (!logAll)
                    return false;
            }
            else {
                synchronized (rcvdEvts) {
                    if (rcvdEvts.size() != exp.size()) {
                        pass = false;

                        log.info("Missed or extra events for key [key=" + key +
                            ", exp=" + e.getValue() +
                            ", rcvd=" + rcvdEvts + ']');

                        if (!logAll)
                            return false;
                    }

                    int cnt = Math.min(rcvdEvts.size(), exp.size());

                    for (int i = 0; i < cnt; i++) {
                        T2<Integer, Integer> expEvt = exp.get(i);
                        CacheEntryEvent<?, ?> rcvdEvt = rcvdEvts.get(i);

                        if (pass) {
                            assertEquals(key, rcvdEvt.getKey());
                            assertEquals(expEvt.get1(), rcvdEvt.getValue());
                        }
                        else {
                            if (!key.equals(rcvdEvt.getKey()) || !expEvt.get1().equals(rcvdEvt.getValue()))
                                log.warning("Missed events. [key=" + key + ", actKey=" + rcvdEvt.getKey()
                                    + ", expVal=" + expEvt.get1() + ", actVal=" + rcvdEvt.getValue() + "]");
                        }
                    }

                    if (!pass) {
                        for (int i = cnt; i < exp.size(); i++) {
                            T2<Integer, Integer> val = exp.get(i);

                            log.warning("Missed events. [key=" + key + ", expVal=" + val.get1()
                                + ", prevVal=" + val.get2() + "]");
                        }
                    }
                }
            }
        }

        if (pass) {
            expEvts.clear();
            lsnr.evts.clear();
        }

        return pass;
    }

    /**
     *
     */
    private static class CacheEventListener1 implements CacheEntryUpdatedListener<Object, Object> {
        /** */
        private volatile CountDownLatch latch;

        /** */
        private GridConcurrentHashSet<Integer> keys = new GridConcurrentHashSet<>();

        /** */
        private ConcurrentHashMap<Object, CacheEntryEvent<?, ?>> evts = new ConcurrentHashMap<>();

        /** */
        private List<CacheEntryEvent<?, ?>> allEvts;

        /** */
        @LoggerResource
        private IgniteLogger log;

        /**
         * @param saveAll Save all events flag.
         */
        CacheEventListener1(boolean saveAll) {
            if (saveAll)
                allEvts = new ArrayList<>();
        }

        /** {@inheritDoc} */
        @Override public void onUpdated(Iterable<CacheEntryEvent<?, ?>> evts) {
            try {
                for (CacheEntryEvent<?, ?> evt : evts) {
                    CountDownLatch latch = this.latch;

                    log.info("Received cache event [evt=" + evt +
                        ", left=" + (latch != null ? latch.getCount() : null) + ']');

                    this.evts.put(evt.getKey(), evt);

                    keys.add((Integer)evt.getKey());

                    if (allEvts != null)
                        allEvts.add(evt);

                    assertTrue(latch != null);
                    assertTrue(latch.getCount() > 0);

                    latch.countDown();

                    if (latch.getCount() == 0) {
                        this.latch = null;

                        keys.clear();
                    }
                }
            }
            catch (Throwable e) {
                err = true;

                log.error("Unexpected error", e);
            }
        }
    }

    /**
     *
     */
    private static class CacheEventListener2 implements CacheEntryUpdatedListener<Object, Object> {
        /** */
        @LoggerResource
        private IgniteLogger log;

        /** */
        private final ConcurrentHashMap<Integer, Integer> vals = new ConcurrentHashMap<>();

        /** */
        private final ConcurrentHashMap<Integer, List<CacheEntryEvent<?, ?>>> evts = new ConcurrentHashMap<>();

        /**
         * @return Count events.
         */
        public int size() {
            int size = 0;

            for (List<CacheEntryEvent<?, ?>> e : evts.values())
                size += e.size();

            return size;
        }

        /** {@inheritDoc} */
        @Override public synchronized void onUpdated(Iterable<CacheEntryEvent<?, ?>> evts)
            throws CacheEntryListenerException  {
            try {
                for (CacheEntryEvent<?, ?> evt : evts) {
                    Integer key = (Integer)evt.getKey();
                    Integer val = (Integer)evt.getValue();

                    assertNotNull(key);
                    assertNotNull(val);

                    Integer prevVal = vals.get(key);

                    boolean dup = false;

                    if (prevVal != null && prevVal.equals(val))
                        dup = true;

                    if (!dup) {
                        vals.put(key, val);

                        List<CacheEntryEvent<?, ?>> keyEvts = this.evts.get(key);

                        if (keyEvts == null) {
                            keyEvts = Collections.synchronizedList(new ArrayList<CacheEntryEvent<?, ?>>());

                            this.evts.put(key, keyEvts);
                        }

                        keyEvts.add(evt);
                    }
                }
            }
            catch (Throwable e) {
                err = true;

                log.error("Unexpected error", e);
            }
        }
    }

    /**
     *
     */
    public static class CacheEventListener3 implements CacheEntryUpdatedListener<Object, Object>,
        CacheEntryEventSerializableFilter<Object, Object> {
        /** Keys. */
        GridConcurrentHashSet<Integer> keys = new GridConcurrentHashSet<>();

        /** Events. */
        private final ConcurrentHashMap<Object, CacheEntryEvent<?, ?>> evts = new ConcurrentHashMap<>();

        /** {@inheritDoc} */
        @Override public void onUpdated(Iterable<CacheEntryEvent<?, ?>> events) throws CacheEntryListenerException {
            for (CacheEntryEvent<?, ?> e : events) {
                Integer key = (Integer)e.getKey();

                keys.add(key);

                assert evts.put(key, e) == null;
            }
        }

        /** {@inheritDoc} */
        @Override public boolean evaluate(CacheEntryEvent<?, ?> e) throws CacheEntryListenerException {
            return (Integer)e.getValue() % 2 == 0;
        }
    }

    /**
     *
     */
    public static class CacheEventFilter implements CacheEntryEventSerializableFilter<Object, Object> {
        /** {@inheritDoc} */
        @Override public boolean evaluate(CacheEntryEvent<?, ?> event) throws CacheEntryListenerException {
            return ((Integer)event.getValue()) >= 0;
        }
    }

    /**
     *
     */
    private static class TestCommunicationSpi extends TcpCommunicationSpi {
        /** */
        @LoggerResource
        private IgniteLogger log;

        /** */
        private volatile boolean skipMsg;

        /** */
        private volatile boolean skipAllMsg;

        /** */
        private volatile AtomicBoolean sndFirstOnly;

        /** {@inheritDoc} */
        @Override public void sendMessage(ClusterNode node, Message msg, IgniteInClosure<IgniteException> ackC)
            throws IgniteSpiException {
            Object msg0 = ((GridIoMessage)msg).message();

            if (skipAllMsg)
                return;

            if (msg0 instanceof GridContinuousMessage) {
                if (skipMsg) {
                    if (log.isDebugEnabled())
                        log.debug("Skip continuous message: " + msg0);

                    return;
                }
                else {
                    AtomicBoolean sndFirstOnly = this.sndFirstOnly;

                    if (sndFirstOnly != null && !sndFirstOnly.compareAndSet(false, true)) {
                        if (log.isDebugEnabled())
                            log.debug("Skip continuous message: " + msg0);

                        return;
                    }
                }
            }

            super.sendMessage(node, msg, ackC);
        }
    }
}