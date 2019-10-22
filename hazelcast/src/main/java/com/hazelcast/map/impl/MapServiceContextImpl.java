/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl;

import com.hazelcast.cluster.ClusterState;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.PartitioningStrategyConfig;
import com.hazelcast.internal.eviction.ExpirationManager;
import com.hazelcast.internal.serialization.DataType;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.internal.util.ConcurrencyUtil;
import com.hazelcast.internal.util.ConstructorFunction;
import com.hazelcast.internal.util.ContextMutexFactory;
import com.hazelcast.internal.util.InvocationUtil;
import com.hazelcast.internal.util.LocalRetryableExecution;
import com.hazelcast.internal.util.collection.PartitionIdSet;
import com.hazelcast.internal.util.comparators.ValueComparator;
import com.hazelcast.internal.util.comparators.ValueComparatorUtil;
import com.hazelcast.internal.util.executor.ManagedExecutorService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.MapInterceptor;
import com.hazelcast.map.impl.event.MapEventPublisher;
import com.hazelcast.map.impl.event.MapEventPublisherImpl;
import com.hazelcast.map.impl.eviction.MapClearExpiredRecordsTask;
import com.hazelcast.map.impl.journal.MapEventJournal;
import com.hazelcast.map.impl.journal.RingbufferMapEventJournalImpl;
import com.hazelcast.map.impl.mapstore.MapDataStore;
import com.hazelcast.map.impl.mapstore.writebehind.NodeWideUsedCapacityCounter;
import com.hazelcast.map.impl.nearcache.MapNearCacheManager;
import com.hazelcast.map.impl.operation.BasePutOperation;
import com.hazelcast.map.impl.operation.BaseRemoveOperation;
import com.hazelcast.map.impl.operation.GetOperation;
import com.hazelcast.map.impl.operation.MapOperationProvider;
import com.hazelcast.map.impl.operation.MapOperationProviders;
import com.hazelcast.map.impl.operation.MapPartitionDestroyOperation;
import com.hazelcast.map.impl.operation.SetOperation;
import com.hazelcast.map.impl.query.AccumulationExecutor;
import com.hazelcast.map.impl.query.AggregationResult;
import com.hazelcast.map.impl.query.AggregationResultProcessor;
import com.hazelcast.map.impl.query.CallerRunsAccumulationExecutor;
import com.hazelcast.map.impl.query.CallerRunsPartitionScanExecutor;
import com.hazelcast.map.impl.query.ParallelAccumulationExecutor;
import com.hazelcast.map.impl.query.ParallelPartitionScanExecutor;
import com.hazelcast.map.impl.query.PartitionScanExecutor;
import com.hazelcast.map.impl.query.PartitionScanRunner;
import com.hazelcast.map.impl.query.QueryEngine;
import com.hazelcast.map.impl.query.QueryEngineImpl;
import com.hazelcast.map.impl.query.QueryResult;
import com.hazelcast.map.impl.query.QueryResultProcessor;
import com.hazelcast.map.impl.query.QueryRunner;
import com.hazelcast.map.impl.query.ResultProcessorRegistry;
import com.hazelcast.map.impl.querycache.NodeQueryCacheContext;
import com.hazelcast.map.impl.querycache.QueryCacheContext;
import com.hazelcast.map.impl.recordstore.DefaultRecordStore;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.map.listener.MapPartitionLostListener;
import com.hazelcast.monitor.impl.LocalMapStatsImpl;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.partition.PartitioningStrategy;
import com.hazelcast.query.impl.DefaultIndexProvider;
import com.hazelcast.query.impl.IndexCopyBehavior;
import com.hazelcast.query.impl.IndexProvider;
import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.query.impl.predicates.QueryOptimizer;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.eventservice.EventFilter;
import com.hazelcast.spi.impl.eventservice.EventRegistration;
import com.hazelcast.spi.impl.eventservice.EventService;
import com.hazelcast.spi.impl.eventservice.impl.TrueEventFilter;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.partition.IPartitionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.hazelcast.internal.util.SetUtil.immutablePartitionIdSet;
import static com.hazelcast.map.impl.ListenerAdapters.createListenerAdapter;
import static com.hazelcast.map.impl.MapListenerFlagOperator.setAndGetListenerFlags;
import static com.hazelcast.map.impl.MapService.SERVICE_NAME;
import static com.hazelcast.query.impl.predicates.QueryOptimizerFactory.newOptimizer;
import static com.hazelcast.spi.impl.executionservice.ExecutionService.QUERY_EXECUTOR;
import static com.hazelcast.spi.impl.operationservice.Operation.GENERIC_PARTITION_ID;
import static com.hazelcast.spi.properties.GroupProperty.AGGREGATION_ACCUMULATION_PARALLEL_EVALUATION;
import static com.hazelcast.spi.properties.GroupProperty.INDEX_COPY_BEHAVIOR;
import static com.hazelcast.spi.properties.GroupProperty.OPERATION_CALL_TIMEOUT_MILLIS;
import static com.hazelcast.spi.properties.GroupProperty.QUERY_PREDICATE_PARALLEL_EVALUATION;
import static java.lang.Thread.currentThread;

/**
 * Default implementation of {@link MapServiceContext}.
 */
@SuppressWarnings("WeakerAccess")
class MapServiceContextImpl implements MapServiceContext {

    private static final long DESTROY_TIMEOUT_SECONDS = 30;

    private final ILogger logger;
    private final NodeEngine nodeEngine;
    private final QueryEngine queryEngine;
    private final EventService eventService;
    private final QueryRunner mapQueryRunner;
    private final MapEventJournal eventJournal;
    private final QueryOptimizer queryOptimizer;
    private final MapEventPublisher mapEventPublisher;
    private final QueryCacheContext queryCacheContext;
    private final ExpirationManager expirationManager;
    private final PartitionScanRunner partitionScanRunner;
    private final MapNearCacheManager mapNearCacheManager;
    private final MapOperationProviders operationProviders;
    private final PartitionContainer[] partitionContainers;
    private final LocalMapStatsProvider localMapStatsProvider;
    private final ResultProcessorRegistry resultProcessorRegistry;
    private final InternalSerializationService serializationService;
    private final MapClearExpiredRecordsTask clearExpiredRecordsTask;
    private final PartitioningStrategyFactory partitioningStrategyFactory;
    private final NodeWideUsedCapacityCounter nodeWideUsedCapacityCounter;
    private final ConstructorFunction<String, MapContainer> mapConstructor;
    private final IndexProvider indexProvider = new DefaultIndexProvider();
    private final ContextMutexFactory contextMutexFactory = new ContextMutexFactory();
    private final AtomicReference<PartitionIdSet> ownedPartitions = new AtomicReference<>();
    private final ConcurrentMap<String, MapContainer> mapContainers = new ConcurrentHashMap<>();

    private MapService mapService;

    @SuppressWarnings("checkstyle:executablestatementcount")
    MapServiceContextImpl(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.serializationService = ((InternalSerializationService) nodeEngine.getSerializationService());
        this.mapConstructor = createMapConstructor();
        this.queryCacheContext = new NodeQueryCacheContext(this);
        this.partitionContainers = createPartitionContainers();
        this.clearExpiredRecordsTask = new MapClearExpiredRecordsTask(partitionContainers, nodeEngine);
        this.expirationManager = new ExpirationManager(clearExpiredRecordsTask, nodeEngine);
        this.mapNearCacheManager = createMapNearCacheManager();
        this.localMapStatsProvider = createLocalMapStatsProvider();
        this.mapEventPublisher = createMapEventPublisherSupport();
        this.eventJournal = createEventJournal();
        this.queryOptimizer = newOptimizer(nodeEngine.getProperties());
        this.resultProcessorRegistry = createResultProcessorRegistry(serializationService);
        this.partitionScanRunner = createPartitionScanRunner();
        this.queryEngine = createMapQueryEngine();
        this.mapQueryRunner = createMapQueryRunner(nodeEngine, queryOptimizer, resultProcessorRegistry, partitionScanRunner);
        this.eventService = nodeEngine.getEventService();
        this.operationProviders = createOperationProviders();
        this.partitioningStrategyFactory = new PartitioningStrategyFactory(nodeEngine.getConfigClassLoader());
        this.nodeWideUsedCapacityCounter = new NodeWideUsedCapacityCounter(nodeEngine.getProperties());
        this.logger = nodeEngine.getLogger(getClass());
    }

    ConstructorFunction<String, MapContainer> createMapConstructor() {
        return mapName -> {
            MapServiceContext mapServiceContext = getService().getMapServiceContext();
            return new MapContainer(mapName, nodeEngine.getConfig(), mapServiceContext);
        };
    }

    // this method is overridden in another context
    MapNearCacheManager createMapNearCacheManager() {
        return new MapNearCacheManager(this);
    }

    // this method is overridden in another context
    MapOperationProviders createOperationProviders() {
        return new MapOperationProviders();
    }

    // this method is overridden in another context
    MapEventPublisherImpl createMapEventPublisherSupport() {
        return new MapEventPublisherImpl(this);
    }

    private MapEventJournal createEventJournal() {
        return new RingbufferMapEventJournalImpl(getNodeEngine(), this);
    }

    protected LocalMapStatsProvider createLocalMapStatsProvider() {
        return new LocalMapStatsProvider(this);
    }

    private QueryEngineImpl createMapQueryEngine() {
        return new QueryEngineImpl(this);
    }

    private PartitionScanRunner createPartitionScanRunner() {
        return new PartitionScanRunner(this);
    }

    protected QueryRunner createMapQueryRunner(NodeEngine nodeEngine, QueryOptimizer queryOptimizer,
                                               ResultProcessorRegistry resultProcessorRegistry,
                                               PartitionScanRunner partitionScanRunner) {
        boolean parallelEvaluation = nodeEngine.getProperties().getBoolean(QUERY_PREDICATE_PARALLEL_EVALUATION);
        PartitionScanExecutor partitionScanExecutor;
        if (parallelEvaluation) {
            int opTimeoutInMillis = nodeEngine.getProperties().getInteger(OPERATION_CALL_TIMEOUT_MILLIS);
            ManagedExecutorService queryExecutorService = nodeEngine.getExecutionService().getExecutor(QUERY_EXECUTOR);
            partitionScanExecutor = new ParallelPartitionScanExecutor(partitionScanRunner, queryExecutorService,
                    opTimeoutInMillis);
        } else {
            partitionScanExecutor = new CallerRunsPartitionScanExecutor(partitionScanRunner);
        }
        return new QueryRunner(this, queryOptimizer, partitionScanExecutor, resultProcessorRegistry);
    }

    private ResultProcessorRegistry createResultProcessorRegistry(SerializationService ss) {
        ResultProcessorRegistry registry = new ResultProcessorRegistry();
        registry.registerProcessor(QueryResult.class, createQueryResultProcessor(ss));
        registry.registerProcessor(AggregationResult.class, createAggregationResultProcessor(ss));
        return registry;
    }

    private QueryResultProcessor createQueryResultProcessor(SerializationService ss) {
        return new QueryResultProcessor(ss);
    }

    private AggregationResultProcessor createAggregationResultProcessor(SerializationService ss) {
        boolean parallelAccumulation = nodeEngine.getProperties().getBoolean(AGGREGATION_ACCUMULATION_PARALLEL_EVALUATION);
        int opTimeoutInMillis = nodeEngine.getProperties().getInteger(OPERATION_CALL_TIMEOUT_MILLIS);
        AccumulationExecutor accumulationExecutor;
        if (parallelAccumulation) {
            ManagedExecutorService queryExecutorService = nodeEngine.getExecutionService().getExecutor(QUERY_EXECUTOR);
            accumulationExecutor = new ParallelAccumulationExecutor(queryExecutorService, ss, opTimeoutInMillis);
        } else {
            accumulationExecutor = new CallerRunsAccumulationExecutor(ss);
        }

        return new AggregationResultProcessor(accumulationExecutor, serializationService);
    }

    private PartitionContainer[] createPartitionContainers() {
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        return new PartitionContainer[partitionCount];
    }

    @Override
    public MapContainer getMapContainer(String mapName) {
        return ConcurrencyUtil.getOrPutSynchronized(mapContainers, mapName, contextMutexFactory, mapConstructor);
    }

    @Override
    public Map<String, MapContainer> getMapContainers() {
        return mapContainers;
    }

    @Override
    public PartitionContainer getPartitionContainer(int partitionId) {
        assert partitionId != GENERIC_PARTITION_ID : "Cannot be called with GENERIC_PARTITION_ID";

        return partitionContainers[partitionId];
    }

    @Override
    public void initPartitionsContainers() {
        final int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            partitionContainers[i] = createPartitionContainer(getService(), i);
        }
    }

    protected PartitionContainer createPartitionContainer(MapService service, int partitionId) {
        return new PartitionContainer(service, partitionId);
    }

    /**
     * Removes all record stores from all partitions.
     *
     * Calls {@link #removeRecordStoresFromPartitionMatchingWith} internally and
     *
     * @param onShutdown           {@code true} if this method is called during map service shutdown,
     *                             otherwise set {@code false}
     * @param onRecordStoreDestroy {@code true} if this method is called during to destroy record store,
     *                             otherwise set {@code false}
     */
    protected void removeAllRecordStoresOfAllMaps(boolean onShutdown, boolean onRecordStoreDestroy) {
        for (PartitionContainer partitionContainer : partitionContainers) {
            if (partitionContainer != null) {
                removeRecordStoresFromPartitionMatchingWith(recordStore -> true,
                        partitionContainer.getPartitionId(), onShutdown, onRecordStoreDestroy);
            }
        }
    }

    @Override
    public void removeRecordStoresFromPartitionMatchingWith(Predicate<RecordStore> predicate,
                                                            int partitionId,
                                                            boolean onShutdown,
                                                            boolean onRecordStoreDestroy) {

        PartitionContainer container = partitionContainers[partitionId];
        if (container == null) {
            return;
        }

        Iterator<RecordStore> partitionIterator = container.getMaps().values().iterator();
        while (partitionIterator.hasNext()) {
            RecordStore partition = partitionIterator.next();
            if (predicate.test(partition)) {
                partition.clearPartition(onShutdown, onRecordStoreDestroy);
                partitionIterator.remove();
            }
        }
    }

    @Override
    public void removeWbqCountersFromMatchingPartitionsWith(Predicate<RecordStore> predicate,
                                                            int partitionId) {

        PartitionContainer container = partitionContainers[partitionId];
        if (container == null) {
            return;
        }

        Iterator<RecordStore> partitionIterator = container.getMaps().values().iterator();
        while (partitionIterator.hasNext()) {
            RecordStore partition = partitionIterator.next();
            if (predicate.test(partition)) {
                partition.getMapDataStore().getTxnReservedCapacityCounter().releaseAllReservations();
            }
        }
    }

    @Override
    public MapService getService() {
        return mapService;
    }

    @Override
    public void setService(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public void destroyMapStores() {
        for (MapContainer mapContainer : mapContainers.values()) {
            MapStoreWrapper store = mapContainer.getMapStoreContext().getMapStoreWrapper();
            if (store != null) {
                store.destroy();
            }
        }
    }

    @Override
    public void flushMaps() {
        for (MapContainer mapContainer : mapContainers.values()) {
            mapContainer.getMapStoreContext().stop();
        }

        for (PartitionContainer partitionContainer : partitionContainers) {
            for (String mapName : mapContainers.keySet()) {
                RecordStore recordStore = partitionContainer.getExistingRecordStore(mapName);
                if (recordStore != null) {
                    MapDataStore mapDataStore = recordStore.getMapDataStore();
                    mapDataStore.hardFlush();
                }
            }
        }
    }

    @Override
    public void destroyMap(String mapName) {
        // on LiteMembers we don't have a MapContainer, but we may have a Near Cache and listeners
        mapNearCacheManager.destroyNearCache(mapName);
        nodeEngine.getEventService().deregisterAllListeners(SERVICE_NAME, mapName);

        MapContainer mapContainer = mapContainers.get(mapName);
        if (mapContainer == null) {
            return;
        }

        nodeEngine.getWanReplicationService().removeWanEventCounters(MapService.SERVICE_NAME, mapName);
        mapContainer.getMapStoreContext().stop();
        localMapStatsProvider.destroyLocalMapStatsImpl(mapContainer.getName());
        destroyPartitionsAndMapContainer(mapContainer);
    }

    /**
     * Destroys the map data on local partition threads and waits for
     * {@value #DESTROY_TIMEOUT_SECONDS} seconds
     * for each partition segment destruction to complete.
     *
     * @param mapContainer the map container to destroy
     */
    private void destroyPartitionsAndMapContainer(MapContainer mapContainer) {
        final List<LocalRetryableExecution> executions = new ArrayList<>();

        for (PartitionContainer container : partitionContainers) {
            final MapPartitionDestroyOperation op = new MapPartitionDestroyOperation(container, mapContainer);
            executions.add(InvocationUtil.executeLocallyWithRetry(nodeEngine, op));
        }

        for (LocalRetryableExecution execution : executions) {
            try {
                if (!execution.awaitCompletion(DESTROY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warning("Map partition was not destroyed in expected time, possible leak");
                }
            } catch (InterruptedException e) {
                currentThread().interrupt();
                nodeEngine.getLogger(getClass()).warning(e);
            }
        }
    }

    @Override
    public void reset() {
        removeAllRecordStoresOfAllMaps(false, false);
        mapNearCacheManager.reset();
    }

    @Override
    public void shutdown() {
        removeAllRecordStoresOfAllMaps(true, false);
        mapNearCacheManager.shutdown();
        mapContainers.clear();
        expirationManager.onShutdown();
    }

    @Override
    public RecordStore getRecordStore(int partitionId, String mapName) {
        return getPartitionContainer(partitionId).getRecordStore(mapName);
    }

    @Override
    public RecordStore getRecordStore(int partitionId, String mapName, boolean skipLoadingOnCreate) {
        return getPartitionContainer(partitionId).getRecordStore(mapName, skipLoadingOnCreate);
    }

    @Override
    public RecordStore getExistingRecordStore(int partitionId, String mapName) {
        return getPartitionContainer(partitionId).getExistingRecordStore(mapName);
    }

    @Override
    public PartitionIdSet getOwnedPartitions() {
        PartitionIdSet partitions = ownedPartitions.get();
        if (partitions == null) {
            reloadOwnedPartitions();
            partitions = ownedPartitions.get();
        }
        return partitions;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The method will set the owned partition set in a CAS loop because
     * this method can be called concurrently.
     */
    @Override
    public void reloadOwnedPartitions() {
        final IPartitionService partitionService = nodeEngine.getPartitionService();
        for (; ; ) {
            final PartitionIdSet expected = ownedPartitions.get();
            final Collection<Integer> partitions = partitionService.getMemberPartitions(nodeEngine.getThisAddress());
            final PartitionIdSet newSet = immutablePartitionIdSet(partitionService.getPartitionCount(), partitions);
            if (ownedPartitions.compareAndSet(expected, newSet)) {
                return;
            }
        }
    }

    @Override
    public ExpirationManager getExpirationManager() {
        return expirationManager;
    }

    @Override
    public NodeEngine getNodeEngine() {
        return nodeEngine;
    }

    @Override
    public MapEventPublisher getMapEventPublisher() {
        return mapEventPublisher;
    }

    @Override
    public MapEventJournal getEventJournal() {
        return eventJournal;
    }

    @Override
    public QueryEngine getQueryEngine(String mapName) {
        return queryEngine;
    }

    @Override
    public QueryRunner getMapQueryRunner(String name) {
        return mapQueryRunner;
    }

    @Override
    public QueryOptimizer getQueryOptimizer() {
        return queryOptimizer;
    }

    @Override
    public LocalMapStatsProvider getLocalMapStatsProvider() {
        return localMapStatsProvider;
    }

    @Override
    public Object toObject(Object data) {
        return serializationService.toObject(data);
    }

    @Override
    public Data toData(Object object, PartitioningStrategy partitionStrategy) {
        return serializationService.toData(object, partitionStrategy);
    }

    @Override
    public Data toData(Object object) {
        return serializationService.toData(object, DataType.HEAP);
    }

    @Override
    public MapClearExpiredRecordsTask getClearExpiredRecordsTask() {
        return clearExpiredRecordsTask;
    }

    // TODO: interceptors should get a wrapped object which includes the serialized version
    @Override
    public Object interceptGet(InterceptorRegistry interceptorRegistry, Object currentValue) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return currentValue;
        }

        Object result = toObject(currentValue);
        for (MapInterceptor interceptor : interceptors) {
            Object temp = interceptor.interceptGet(result);
            if (temp != null) {
                result = temp;
            }
        }
        return result == null ? currentValue : result;
    }

    @Override
    public void interceptAfterGet(InterceptorRegistry interceptorRegistry, Object value) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return;
        }

        value = toObject(value);
        for (MapInterceptor interceptor : interceptors) {
            interceptor.afterGet(value);
        }
    }

    @Override
    public Object interceptPut(InterceptorRegistry interceptorRegistry, Object oldValue, Object newValue) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return newValue;
        }

        Object result = toObject(newValue);
        oldValue = toObject(oldValue);
        for (MapInterceptor interceptor : interceptors) {
            Object temp = interceptor.interceptPut(oldValue, result);
            if (temp != null) {
                result = temp;
            }
        }
        return result;
    }

    @Override
    public void interceptAfterPut(InterceptorRegistry interceptorRegistry, Object newValue) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return;
        }

        newValue = toObject(newValue);
        for (MapInterceptor interceptor : interceptors) {
            interceptor.afterPut(newValue);
        }
    }

    @Override
    public Object interceptRemove(InterceptorRegistry interceptorRegistry, Object value) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return value;
        }

        Object result = toObject(value);
        for (MapInterceptor interceptor : interceptors) {
            Object temp = interceptor.interceptRemove(result);
            if (temp != null) {
                result = temp;
            }
        }
        return result;
    }

    @Override
    public void interceptAfterRemove(InterceptorRegistry interceptorRegistry, Object value) {
        List<MapInterceptor> interceptors = interceptorRegistry.getInterceptors();
        if (interceptors.isEmpty()) {
            return;
        }

        value = toObject(value);
        for (MapInterceptor interceptor : interceptors) {
            interceptor.afterRemove(value);
        }
    }

    @Override
    public void addInterceptor(String id, String mapName, MapInterceptor interceptor) {
        MapContainer mapContainer = getMapContainer(mapName);
        mapContainer.getInterceptorRegistry().register(id, interceptor);
    }

    @Override
    public boolean removeInterceptor(String mapName, String id) {
        MapContainer mapContainer = getMapContainer(mapName);
        return mapContainer.getInterceptorRegistry().deregister(id);
    }

    @Override
    public String generateInterceptorId(String mapName, MapInterceptor interceptor) {
        return interceptor.getClass().getName() + interceptor.hashCode();
    }

    @Override
    public UUID addLocalEventListener(Object listener, String mapName) {
        EventRegistration registration = addListenerInternal(listener, TrueEventFilter.INSTANCE, mapName, true);
        return registration.getId();
    }

    @Override
    public UUID addLocalEventListener(Object listener, EventFilter eventFilter, String mapName) {
        EventRegistration registration = addListenerInternal(listener, eventFilter, mapName, true);
        return registration.getId();
    }

    @Override
    public UUID addLocalPartitionLostListener(MapPartitionLostListener listener, String mapName) {
        ListenerAdapter listenerAdapter = new InternalMapPartitionLostListenerAdapter(listener);
        EventFilter filter = new MapPartitionLostEventFilter();
        EventRegistration registration = eventService.registerLocalListener(SERVICE_NAME, mapName, filter, listenerAdapter);
        return registration.getId();
    }

    @Override
    public UUID addEventListener(Object listener, EventFilter eventFilter, String mapName) {
        EventRegistration registration = addListenerInternal(listener, eventFilter, mapName, false);
        return registration.getId();
    }

    @Override
    public UUID addPartitionLostListener(MapPartitionLostListener listener, String mapName) {
        ListenerAdapter listenerAdapter = new InternalMapPartitionLostListenerAdapter(listener);
        EventFilter filter = new MapPartitionLostEventFilter();
        EventRegistration registration = eventService.registerListener(SERVICE_NAME, mapName, filter, listenerAdapter);
        return registration.getId();
    }

    private EventRegistration addListenerInternal(Object listener, EventFilter filter, String mapName, boolean local) {
        ListenerAdapter listenerAdaptor = createListenerAdapter(listener);
        if (!(filter instanceof EventListenerFilter)) {
            int enabledListeners = setAndGetListenerFlags(listenerAdaptor);
            filter = new EventListenerFilter(enabledListeners, filter);
        }

        if (local) {
            return eventService.registerLocalListener(SERVICE_NAME, mapName, filter, listenerAdaptor);
        } else {
            return eventService.registerListener(SERVICE_NAME, mapName, filter, listenerAdaptor);
        }
    }

    @Override
    public boolean removeEventListener(String mapName, UUID registrationId) {
        return eventService.deregisterListener(SERVICE_NAME, mapName, registrationId);
    }

    @Override
    public boolean removePartitionLostListener(String mapName, UUID registrationId) {
        return eventService.deregisterListener(SERVICE_NAME, mapName, registrationId);
    }

    @Override
    public MapOperationProvider getMapOperationProvider(String mapName) {
        return operationProviders.getOperationProvider(mapName);
    }

    @Override
    public IndexProvider getIndexProvider(MapConfig mapConfig) {
        return indexProvider;
    }

    @Override
    public Extractors getExtractors(String mapName) {
        MapContainer mapContainer = getMapContainer(mapName);
        return mapContainer.getExtractors();
    }

    @Override
    public void incrementOperationStats(long startTime, LocalMapStatsImpl localMapStats, String mapName, Operation operation) {
        final long durationNanos = System.nanoTime() - startTime;
        if (operation instanceof SetOperation) {
            localMapStats.incrementSetLatencyNanos(durationNanos);
        } else if (operation instanceof BasePutOperation) {
            localMapStats.incrementPutLatencyNanos(durationNanos);
        } else if (operation instanceof BaseRemoveOperation) {
            localMapStats.incrementRemoveLatencyNanos(durationNanos);
        } else if (operation instanceof GetOperation) {
            localMapStats.incrementGetLatencyNanos(durationNanos);
        }
    }

    @Override
    public RecordStore createRecordStore(MapContainer mapContainer, int partitionId, MapKeyLoader keyLoader) {
        assert partitionId != GENERIC_PARTITION_ID : "Cannot be called with GENERIC_PARTITION_ID";

        ILogger logger = nodeEngine.getLogger(DefaultRecordStore.class);
        return new DefaultRecordStore(mapContainer, partitionId, keyLoader, logger);
    }

    @Override
    public boolean removeMapContainer(MapContainer mapContainer) {
        return mapContainers.remove(mapContainer.getName(), mapContainer);
    }

    @Override
    public PartitioningStrategy getPartitioningStrategy(String mapName, PartitioningStrategyConfig config) {
        return partitioningStrategyFactory.getPartitioningStrategy(mapName, config);
    }

    @Override
    public void removePartitioningStrategyFromCache(String mapName) {
        partitioningStrategyFactory.removePartitioningStrategyFromCache(mapName);
    }

    @Override
    public PartitionContainer[] getPartitionContainers() {
        return partitionContainers;
    }

    @Override
    public void onClusterStateChange(ClusterState newState) {
        expirationManager.onClusterStateChange(newState);
    }

    @Override
    public ResultProcessorRegistry getResultProcessorRegistry() {
        return resultProcessorRegistry;
    }

    @Override
    public MapNearCacheManager getMapNearCacheManager() {
        return mapNearCacheManager;
    }

    @Override
    public UUID addListenerAdapter(ListenerAdapter listenerAdaptor, EventFilter eventFilter, String mapName) {
        EventRegistration registration = getNodeEngine().getEventService().
                registerListener(MapService.SERVICE_NAME, mapName, eventFilter, listenerAdaptor);
        return registration.getId();
    }

    @Override
    public UUID addLocalListenerAdapter(ListenerAdapter adapter, String mapName) {
        EventService eventService = getNodeEngine().getEventService();
        EventRegistration registration = eventService.registerLocalListener(MapService.SERVICE_NAME, mapName, adapter);
        return registration.getId();
    }

    @Override
    public QueryCacheContext getQueryCacheContext() {
        return queryCacheContext;
    }

    @Override
    public IndexCopyBehavior getIndexCopyBehavior() {
        return nodeEngine.getProperties().getEnum(INDEX_COPY_BEHAVIOR, IndexCopyBehavior.class);
    }

    @Override
    public ValueComparator getValueComparatorOf(InMemoryFormat inMemoryFormat) {
        return ValueComparatorUtil.getValueComparatorOf(inMemoryFormat);
    }

    public NodeWideUsedCapacityCounter getNodeWideUsedCapacityCounter() {
        return nodeWideUsedCapacityCounter;
    }

    // used only for testing purposes
    PartitioningStrategyFactory getPartitioningStrategyFactory() {
        return partitioningStrategyFactory;
    }
}
