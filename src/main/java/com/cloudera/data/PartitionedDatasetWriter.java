package com.cloudera.data;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

public class PartitionedDatasetWriter<E> implements DatasetWriter<E>, Closeable {

  private static final Logger logger = LoggerFactory
      .getLogger(PartitionedDatasetWriter.class);

  private final Dataset dataset;
  private int maxWriters;

  private final PartitionStrategy partitionStrategy;
  private LoadingCache<Object[], DatasetWriter<E>> cachedWriters;

  public PartitionedDatasetWriter(Dataset dataset) {
    Preconditions.checkArgument(dataset.isPartitioned(), "Dataset " + dataset
        + " is not partitioned");

    this.dataset = dataset;
    this.partitionStrategy = dataset.getPartitionStrategy();
    this.maxWriters = Math.min(10, dataset.getPartitionStrategy()
        .getCardinality());
  }

  class DatasetWriterCacheLoader extends CacheLoader<Object[], DatasetWriter<E>> {
    @Override
    public DatasetWriter<E> load(Object[] key) throws Exception {
      Dataset partition = dataset.getPartition(key, true);
      DatasetWriter<E> writer = partition.getWriter();

      writer.open();
      return writer;
    }
  }

  class DatasetWriterRemovalStrategy implements RemovalListener<Object[], DatasetWriter<E>> {
    @Override
    public void onRemoval(
        RemovalNotification<Object[], DatasetWriter<E>> notification) {

      DatasetWriter<E> writer = notification.getValue();

      logger.debug("Removing writer:{} for partition:{}", writer,
          notification.getKey());

      try {
        writer.close();
      } catch (IOException e) {
        logger
            .error(
                "Failed to close the dataset writer:{} - {} (this may cause problems)",
                writer, e.getMessage());
        logger.debug("Stack trace follows:", e);
      }
    }
  }

  @Override
  public void open() {
    logger.debug("Opening partitioned dataset writer w/strategy:{}",
        partitionStrategy);

    cachedWriters = CacheBuilder.newBuilder().maximumSize(maxWriters)
        .removalListener(new DatasetWriterRemovalStrategy())
        .build(new DatasetWriterCacheLoader());
  }

  @Override
  public void write(E entity) throws IOException {
    Object[] key = partitionStrategy.getPartitionKey(entity);
    DatasetWriter<E> writer = null;

    try {
      writer = cachedWriters.get(key);
    } catch (ExecutionException e) {
      throw new IOException("Unable to get a writer for entity:" + entity
          + " partition key:" + Arrays.asList(key), e);
    }

    writer.write(entity);
  }

  @Override
  public void flush() throws IOException {
    logger.debug("Flushing all cached writers for partition:{}",
        partitionStrategy.getName());

    /*
     * There's a potential for flushing entries that are created by other
     * threads while looping through the writers. While normally just wasteful,
     * on HDFS, this is particularly bad. We should probably do something about
     * this, but it will be difficult as Cache (ideally) uses multiple
     * partitions to prevent cached writer contention.
     */
    for (DatasetWriter<E> writer : cachedWriters.asMap().values()) {
      logger.debug("Flushing partition writer:{}.{}",
          partitionStrategy.getName(), writer);
      writer.flush();
    }
  }

  @Override
  public void close() throws IOException {
    logger.debug("Closing all cached writers for partition:{}",
        partitionStrategy.getName());

    for (DatasetWriter<E> writer : cachedWriters.asMap().values()) {
      logger.debug("Closing partition writer:{}.{}",
          partitionStrategy.getName(), writer);
      writer.close();
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("partitionStrategy", partitionStrategy)
        .add("maxWriters", maxWriters).add("dataset", dataset)
        .add("cachedWriters", cachedWriters).toString();
  }

}
