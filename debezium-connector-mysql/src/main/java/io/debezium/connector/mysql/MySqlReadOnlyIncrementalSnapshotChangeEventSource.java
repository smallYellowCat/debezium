/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.connector.mysql.signal.ExecuteSnapshotKafkaSignal;
import io.debezium.connector.mysql.signal.KafkaSignalThread;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.snapshot.incremental.AbstractIncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.schema.DataCollectionId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;

/**
 * A MySQL specific read-only incremental snapshot change event source.
 * Uses executed GTID set as low/high watermarks for incremental snapshot window to support read-only connection.
 * <p>
 * <b>Prerequisites</b>
 * <ul>
 *      <li> gtid_mode=ON </li>
 *      <li> enforce_gtid_consistency=ON </li>
 *      <li> If the connector is reading from a replica, then for multithreaded replicas (replicas on which replica_parallel_workers is set to a value greater than 0) it’s required to set replica_preserve_commit_order=1 or slave_preserve_commit_order=1</li>
 * </ul>
 * </p>
 * <p>
 * <b>When a chunk should be snapshotted</b>
 * <ul>
 *      <li> streaming is paused (this is implicit when the watermarks are handled) </li>
 *      <li> a SHOW MASTER STATUS query is executed and the low watermark is set to executed_gtid_set </li>
 *      <li> a new data chunk is read from a database by generating the SELECT statement and placed into a window buffer keyed by primary keys </li>
 *      <li> a SHOW MASTER STATUS  query is executed and the high watermark is set to executed_gtid_set from SHOW MASTER STATUS subtract low watermark. In case the high watermark contains more than one unique server UUID value, steps 2 - 4 get redone </li>
 *      <li> streaming is resumed </li>
 * </ul>
 * </p>
 * <p>
 * <b>During the subsequent streaming</b>
 * <ul>
 *      <li> if binlog event is received and its GTID is outside of the low watermark GTID set then window processing mode is enabled </li>
 *      <li> if binlog event is received and its GTID is outside of the high watermark GTID set then window processing mode is disabled and the rest of the window’s buffer is streamed </li>
 *      <li> if server heartbeat event is received and its GTID reached the largest transaction id of high watermark then window processing mode is disabled and the rest of the window’s buffer is streamed </li>
 *      <li> if window processing mode is enabled then if the event key is contained in the window buffer then it is removed from the window buffer </li>
 *      <li> event is streamed </li>
 * </ul>
 * </p>
 * <br/>
 * <b>Watermark checks</b>
 * <p>If a watermark's GTID set doesn’t contain a binlog event’s GTID then the watermark is passed and the window processing mode gets updated. Multiple binlog events can have the same GTID, this is why the algorithm waits for the binlog event with GTID outside of watermark’s GTID set to close the window, instead of closing it as soon as the largest transaction id is reached.</p>
 * <p>The deduplication starts with the first event after the low watermark because up to the point when GTID is contained in the low watermark (executed_gtid_set that was captured before the chunk select statement). A COMMIT after the low watermark is used to make sure a chunk selection sees the changes that are committed before its execution. </p>
 * <p>The deduplication continues for all the events that are in the high watermark. The deduplicated chunk events are inserted right before the first event that is outside of the high watermark.</p>
 * <br/>
 * <b>No binlog events</b>
 * <p>Server heartbeat events (events that are sent by a primary to a replica to let the replica know that the primary is still alive) are used to update the window processing mode when the rate of binlog updates is low. Server heartbeat is sent only if there are no binlog events for the duration of a heartbeat interval.</p>
 * <p>The heartbeat has the same GTID as the latest binlog event at the moment (it’s a technical event that doesn’t get written into the output stream, but can be used in events processing logic). In case there are zero updates after the chunk selection, the server heartbeat’s GTID will be within a high watermark. This is why for server heartbeat event’s GTID it’s enough to reach the largest transaction id of a high watermark to disable the window processing mode, send a chunk and proceed to the next one.</p>
 * <p>The server UUID part of heartbeat’s GTID is used to get the max transaction id of a high watermark for the same server UUID. High watermark is set to a difference between executed_gtid_set before and after chunk selection. If a high watermark contains more than one unique server UUID the chunk selection is redone and watermarks are recaptured. This is done to avoid the scenario when the window is closed too early by heartbeat because server UUID changes between high and low watermarks. Heartbeat doesn’t need to check the window processing mode, it doesn’t affect correctness and simplifies the checks for the cases when the binlog reader was up to date with the low watermark and when there are no new events between high and low watermarks.</p>
 * <br/>
 * <b>No changes between watermarks</b>
 * <p>A window can be opened and closed right away by the same event. This can happen when a high watermark is an empty set, which means there were no binlog events during the chunk select. Chunk will get inserted right after the low watermark, no events will be deduplicated from the chunk</p>
 * <br/>
 * <b>No updates for included tables</b>
 * <p>It’s important to receive binlog events for the incremental snapshot to make progress. All binlog events are checked against the low and high watermarks, including the events from the tables that aren’t included in the connector. This guarantees that the window processing mode gets updated even when none of the tables included in the connector are getting binlog events.</p>
 */
public class MySqlReadOnlyIncrementalSnapshotChangeEventSource<T extends DataCollectionId> extends AbstractIncrementalSnapshotChangeEventSource<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySqlReadOnlyIncrementalSnapshotChangeEventSource.class);
    private final String showMasterStmt = "SHOW MASTER STATUS";
    private final KafkaSignalThread<T> kafkaSignal;

    public MySqlReadOnlyIncrementalSnapshotChangeEventSource(RelationalDatabaseConnectorConfig config,
                                                             JdbcConnection jdbcConnection,
                                                             EventDispatcher<T> dispatcher,
                                                             DatabaseSchema<?> databaseSchema,
                                                             Clock clock,
                                                             SnapshotProgressListener progressListener,
                                                             DataChangeEventListener dataChangeEventListener) {
        super(config, jdbcConnection, dispatcher, databaseSchema, clock, progressListener, dataChangeEventListener);
        kafkaSignal = new KafkaSignalThread<>(MySqlConnector.class, config, this);
    }

    @Override
    public void init(OffsetContext offsetContext) {
        super.init(offsetContext);
        Long signalOffset = getContext().getSignalOffset();
        if (signalOffset != null) {
            kafkaSignal.seek(signalOffset);
        }
        kafkaSignal.start();
    }

    @Override
    public void processMessage(Partition partition, DataCollectionId dataCollectionId, Object key, OffsetContext offsetContext) throws InterruptedException {
        if (getContext() == null) {
            LOGGER.warn("Context is null, skipping message processing");
            return;
        }
        checkEnqueuedSnapshotSignals(offsetContext);
        LOGGER.trace("Checking window for table '{}', key '{}', window contains '{}'", dataCollectionId, key, window);
        boolean windowClosed = getContext().updateWindowState(offsetContext);
        if (windowClosed) {
            sendWindowEvents(partition, offsetContext);
            readChunk();
        }
        else if (!window.isEmpty() && getContext().deduplicationNeeded()) {
            deduplicateWindow(dataCollectionId, key);
        }
    }

    @Override
    public void processHeartbeat(Partition partition, OffsetContext offsetContext) throws InterruptedException {
        if (getContext() == null) {
            LOGGER.warn("Context is null, skipping message processing");
            return;
        }
        checkEnqueuedSnapshotSignals(offsetContext);
        while (getContext().snapshotRunning() && getContext().reachedHighWatermark(offsetContext)) {
            sendWindowEvents(partition, offsetContext);
            readChunk();
        }
    }

    @Override
    public void processFilteredEvent(Partition partition, OffsetContext offsetContext) throws InterruptedException {
        if (getContext() == null) {
            LOGGER.warn("Context is null, skipping message processing");
            return;
        }
        checkEnqueuedSnapshotSignals(offsetContext);
        boolean windowClosed = getContext().updateWindowState(offsetContext);
        if (windowClosed) {
            sendWindowEvents(partition, offsetContext);
            readChunk();
        }
    }

    public void enqueueDataCollectionNamesToSnapshot(List<String> dataCollectionIds, long signalOffset) {
        getContext().enqueueDataCollectionsToSnapshot(dataCollectionIds, signalOffset);
    }

    @Override
    public void processTransactionStartedEvent(Partition partition, OffsetContext offsetContext) throws InterruptedException {
        if (getContext() == null) {
            LOGGER.warn("Context is null, skipping message processing");
            return;
        }
        boolean windowClosed = getContext().updateWindowState(offsetContext);
        if (windowClosed) {
            sendWindowEvents(partition, offsetContext);
            readChunk();
        }
    }

    @Override
    public void processTransactionCommittedEvent(Partition partition, OffsetContext offsetContext) throws InterruptedException {
        if (getContext() == null) {
            LOGGER.warn("Context is null, skipping message processing");
            return;
        }
        while (getContext().snapshotRunning() && getContext().reachedHighWatermark(offsetContext)) {
            sendWindowEvents(partition, offsetContext);
            readChunk();
        }
    }

    protected void updateLowWatermark() {
        try {
            getExecutedGtidSet(getContext()::setLowWatermark);
            // it is required that the chunk selection sees the changes that are committed before its execution
            jdbcConnection.commit();
        }
        catch (SQLException e) {
            throw new DebeziumException(e);
        }
    }

    protected void updateHighWatermark() {
        getExecutedGtidSet(getContext()::setHighWatermark);
    }

    private void getExecutedGtidSet(Consumer<GtidSet> watermark) {
        try {
            jdbcConnection.query(showMasterStmt, rs -> {
                if (rs.next()) {
                    if (rs.getMetaData().getColumnCount() > 4) {
                        // This column exists only in MySQL 5.6.5 or later ...
                        final String gtidSet = rs.getString(5); // GTID set, may be null, blank, or contain a GTID set
                        watermark.accept(new GtidSet(gtidSet));
                    }
                    else {
                        throw new UnsupportedOperationException("Need to add support for executed GTIDs for versions prior to 5.6.5");
                    }
                }
            });
        }
        catch (SQLException e) {
            throw new DebeziumException(e);
        }
    }

    @Override
    protected void emitWindowOpen() {
        updateLowWatermark();
    }

    @Override
    protected void emitWindowClose() throws InterruptedException {
        updateHighWatermark();
        if (getContext().serverUuidChanged()) {
            rereadChunk();
        }
    }

    public void rereadChunk() throws InterruptedException {
        if (context == null) {
            return;
        }
        if (!context.snapshotRunning() || !context.deduplicationNeeded() || window.isEmpty()) {
            return;
        }
        window.clear();
        context.revertChunk();
        readChunk();
    }

    private void checkEnqueuedSnapshotSignals(OffsetContext offsetContext) throws InterruptedException {
        while (getContext().hasExecuteSnapshotSignals()) {
            addDataCollectionNamesToSnapshot(getContext().getExecuteSnapshotSignals(), offsetContext);
        }
    }

    private void addDataCollectionNamesToSnapshot(ExecuteSnapshotKafkaSignal executeSnapshotSignal, OffsetContext offsetContext) throws InterruptedException {
        super.addDataCollectionNamesToSnapshot(executeSnapshotSignal.getDataCollections(), offsetContext);
        getContext().setSignalOffset(executeSnapshotSignal.getSignalOffset());
    }

    private MySqlReadOnlyIncrementalSnapshotContext<T> getContext() {
        return (MySqlReadOnlyIncrementalSnapshotContext<T>) context;
    }
}