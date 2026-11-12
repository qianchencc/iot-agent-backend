package com.ehe.iotagent.readmodel.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.ehe.iotagent.readmodel.ReadSnapshots;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReadModelConfiguration {
    @Bean(destroyMethod = "close")
    SnapshotRuntime snapshotRuntime(ObjectProvider<ReadSnapshots.Persistence> store,
            ObjectProvider<ReadSnapshots.Barrier> barrier, ObjectMapper mapper) {
        return new SnapshotRuntime(store.getIfAvailable(), barrier.getIfAvailable(), mapper);
    }

    @Bean(destroyMethod = "")
    ReadSnapshots readSnapshots(SnapshotRuntime runtime) { return runtime.snapshots; }

    public static final class SnapshotRuntime implements AutoCloseable {
        private final ThreadPoolExecutor upstream = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), threads("snapshot-upstream"), new ThreadPoolExecutor.AbortPolicy());
        // Redis operations are serialized so deletes follow writes; a blocked Redis cannot occupy upstream workers.
        private final ThreadPoolExecutor storage = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4096), threads("snapshot-storage"), new ThreadPoolExecutor.AbortPolicy());
        private final ScheduledThreadPoolExecutor cadence = new ScheduledThreadPoolExecutor(1, threads("snapshot-cadence"));
        private final ReadSnapshots snapshots;
        SnapshotRuntime(ReadSnapshots.Persistence store, ReadSnapshots.Barrier barrier, ObjectMapper mapper) {
            snapshots = ReadSnapshots.create(Clock.systemUTC(), upstream, storage, store, barrier, mapper,
                    ReadSnapshots.Settings.defaults());
            cadence.setRemoveOnCancelPolicy(true);
            cadence.scheduleWithFixedDelay(snapshots::refreshActive, 5, 5, TimeUnit.SECONDS);
        }
        @Override public void close() {
            cadence.shutdownNow();
            upstream.shutdown();
            storage.shutdown();
            boolean drained = false;
            try {
                // Both queues must settle before the recovery checkpoint may claim a clean shutdown.
                boolean storageDrained = storage.awaitTermination(3, TimeUnit.SECONDS);
                boolean upstreamDrained = upstream.awaitTermination(3, TimeUnit.SECONDS);
                drained = storageDrained && upstreamDrained;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                snapshots.close(drained);
                upstream.shutdownNow();
                storage.shutdownNow();
            }
        }
    }
    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
