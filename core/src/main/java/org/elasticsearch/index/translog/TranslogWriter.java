/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.translog;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.OutputStreamDataOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.Channels;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.shard.ShardId;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

public class TranslogWriter extends BaseTranslogReader implements Closeable {

    public static final String TRANSLOG_CODEC = "translog";
    public static final int VERSION_CHECKSUMS = 1;
    public static final int VERSION_CHECKPOINTS = 2; // since 2.0 we have checkpoints?
    public static final int VERSION = VERSION_CHECKPOINTS;

    private final ShardId shardId;
    /* the offset in bytes that was written when the file was last synced*/
    private volatile long lastSyncedOffset;
    /* the number of translog operations written to this file */
    private volatile int operationCounter;
    /* if we hit an exception that we can't recover from we assign it to this var and ship it with every AlreadyClosedException we throw */
    private volatile Throwable tragedy;
    /* A buffered outputstream what writes to the writers channel */
    private final OutputStream outputStream;
    /* the total offset of this file including the bytes written to the file as well as into the buffer */
    private volatile long totalOffset;

    protected final AtomicBoolean closed = new AtomicBoolean(false);


    public TranslogWriter(ShardId shardId, long generation, FileChannel channel, Path path, ByteSizeValue bufferSize) throws IOException {
        super(generation, channel, path, channel.position());
        this.shardId = shardId;
        this.outputStream = new BufferedChannelOutputStream(java.nio.channels.Channels.newOutputStream(channel), bufferSize.bytesAsInt());
        this.lastSyncedOffset = channel.position();
        totalOffset = lastSyncedOffset;
    }

    static int getHeaderLength(String translogUUID) {
        return getHeaderLength(new BytesRef(translogUUID).length);
    }

    private static int getHeaderLength(int uuidLength) {
        return CodecUtil.headerLength(TRANSLOG_CODEC) + uuidLength + RamUsageEstimator.NUM_BYTES_INT;
    }

    public static TranslogWriter create(ShardId shardId, String translogUUID, long fileGeneration, Path file, ChannelFactory channelFactory, ByteSizeValue bufferSize) throws IOException {
        final BytesRef ref = new BytesRef(translogUUID);
        final int headerLength = getHeaderLength(ref.length);
        final FileChannel channel = channelFactory.open(file);
        try {
            // This OutputStreamDataOutput is intentionally not closed because
            // closing it will close the FileChannel
            final OutputStreamDataOutput out = new OutputStreamDataOutput(java.nio.channels.Channels.newOutputStream(channel));
            CodecUtil.writeHeader(out, TRANSLOG_CODEC, VERSION);
            out.writeInt(ref.length);
            out.writeBytes(ref.bytes, ref.offset, ref.length);
            channel.force(true);
            writeCheckpoint(headerLength, 0, file.getParent(), fileGeneration, StandardOpenOption.WRITE);
            final TranslogWriter writer = new TranslogWriter(shardId, fileGeneration, channel, file, bufferSize);
            return writer;
        } catch (Throwable throwable) {
            // if we fail to bake the file-generation into the checkpoint we stick with the file and once we recover and that
            // file exists we remove it. We only apply this logic to the checkpoint.generation+1 any other file with a higher generation is an error condition
            IOUtils.closeWhileHandlingException(channel);
            throw throwable;
        }
    }

    /**
     * If this {@code TranslogWriter} was closed as a side-effect of a tragic exception,
     * e.g. disk full while flushing a new segment, this returns the root cause exception.
     * Otherwise (no tragic exception has occurred) it returns null.
     */
    public Throwable getTragicException() {
        return tragedy;
    }

    private synchronized final void closeWithTragicEvent(Throwable throwable) throws IOException {
        assert throwable != null : "throwable must not be null in a tragic event";
        if (tragedy == null) {
            tragedy = throwable;
        } else if (tragedy != throwable) {
            // it should be safe to call closeWithTragicEvents on multiple layers without
            // worrying about self suppression.
            tragedy.addSuppressed(throwable);
        }
        close();
    }

    /**
     * add the given bytes to the translog and return the location they were written at
     */
    public synchronized Translog.Location add(BytesReference data) throws IOException {
        ensureOpen();
        final long offset = totalOffset;
        try {
            data.writeTo(outputStream);
        } catch (Throwable ex) {
            closeWithTragicEvent(ex);
            throw ex;
        }
        totalOffset += data.length();
        operationCounter++;
        return new Translog.Location(generation, offset, data.length());
    }

    /**
     * write all buffered ops to disk and fsync file.
     *
     * Note: any exception during the sync process will be interpreted as a tragic exception and the writer will be closed before
     * raising the exception.
     */
    public void sync() throws IOException {
        if (syncNeeded()) {
            synchronized (this) {
                ensureOpen();
                final long offsetToSync;
                final int opsCounter;
                try {
                    outputStream.flush();
                    offsetToSync = totalOffset;
                    opsCounter = operationCounter;
                    checkpoint(offsetToSync, opsCounter, generation, channel, path);
                } catch (Throwable ex) {
                    closeWithTragicEvent(ex);
                    throw ex;
                }
                lastSyncedOffset = offsetToSync;
            }
        }
    }

    /**
     * returns true if there are buffered ops
     */
    public boolean syncNeeded() { return totalOffset != lastSyncedOffset; }

    @Override
    public int totalOperations() {
        return operationCounter;
    }

    @Override
    public long sizeInBytes() {
        return totalOffset;
    }

    /**
     * closes this writer and transfers it's underlying file channel to a new immutable reader
     */
    public synchronized TranslogReader closeIntoReader() throws IOException {
        try {
            sync(); // sync before we close..
        } catch (IOException e) {
            closeWithTragicEvent(e);
            throw e;
        }
        if (closed.compareAndSet(false, true)) {
            boolean success = false;
            try {
                final TranslogReader reader = new TranslogReader(generation, channel, path, firstOperationOffset, getWrittenOffset(), operationCounter);
                success = true;
                return reader;
            } finally {
                if (success == false) {
                    // close the channel, as we are closed and failed to create a new reader
                    IOUtils.closeWhileHandlingException(channel);
                }
            }
        } else {
            throw new AlreadyClosedException("translog [" + getGeneration() + "] is already closed (path [" + path + "]", tragedy);
        }
    }


    @Override
    public synchronized Translog.Snapshot newSnapshot() {
        ensureOpen();
        try {
            sync();
        } catch (IOException e) {
            throw new TranslogException(shardId, "exception while syncing before creating a snapshot", e);
        }
        return super.newSnapshot();
    }

    private long getWrittenOffset() throws IOException {
        return channel.position();
    }

    /**
     * Syncs the translog up to at least the given offset unless already synced
     *
     * @return <code>true</code> if this call caused an actual sync operation
     */
    public boolean syncUpTo(long offset) throws IOException {
        if (lastSyncedOffset < offset) {
            sync();
            return true;
        }
        return false;
    }

    @Override
    protected void readBytes(ByteBuffer targetBuffer, long position) throws IOException {
        if (position + targetBuffer.remaining() > getWrittenOffset()) {
            synchronized (this) {
                // we only flush here if it's really really needed - try to minimize the impact of the read operation
                // in some cases ie. a tragic event we might still be able to read the relevant value
                // which is not really important in production but some test can make most strict assumptions
                // if we don't fail in this call unless absolutely necessary.
                if (position + targetBuffer.remaining() > getWrittenOffset()) {
                    outputStream.flush();
                }
            }
        }
        // we don't have to have a lock here because we only write ahead to the file, so all writes has been complete
        // for the requested location.
        Channels.readFromFileChannelWithEofException(channel, position, targetBuffer);
    }

    private synchronized void checkpoint(long lastSyncPosition, int operationCounter, long generation, FileChannel translogFileChannel, Path translogFilePath) throws IOException {
        translogFileChannel.force(false);
        writeCheckpoint(lastSyncPosition, operationCounter, translogFilePath.getParent(), generation, StandardOpenOption.WRITE);
    }

    private static void writeCheckpoint(long syncPosition, int numOperations, Path translogFile, long generation, OpenOption... options) throws IOException {
        final Path checkpointFile = translogFile.resolve(Translog.CHECKPOINT_FILE_NAME);
        Checkpoint checkpoint = new Checkpoint(syncPosition, numOperations, generation);
        Checkpoint.write(checkpointFile, checkpoint, options);
    }

    static class ChannelFactory {

        static final ChannelFactory DEFAULT = new ChannelFactory();

        // only for testing until we have a disk-full FileSystemt
        public FileChannel open(Path file) throws IOException {
            return FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE_NEW);
        }
    }

    protected final void ensureOpen() {
        if (isClosed()) {
            throw new AlreadyClosedException("translog [" + getGeneration() + "] is already closed", tragedy);
        }
    }

    @Override
    public final void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            channel.close();
        }
    }

    protected final boolean isClosed() {
        return closed.get();
    }


    private final class BufferedChannelOutputStream extends BufferedOutputStream {

        public BufferedChannelOutputStream(OutputStream out, int size) throws IOException {
            super(out, size);
        }

        @Override
        public synchronized void flush() throws IOException {
            if (count > 0) {
                try {
                    ensureOpen();
                    super.flush();
                } catch (Throwable ex) {
                    closeWithTragicEvent(ex);
                    throw ex;
                }
            }
        }

        @Override
        public void close() throws IOException {
            // the stream is intentionally not closed because
            // closing it will close the FileChannel
            throw new IllegalStateException("never close this stream");
        }
    }
}
