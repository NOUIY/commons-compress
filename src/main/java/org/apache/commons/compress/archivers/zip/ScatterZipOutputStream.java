/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.archivers.zip;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

import org.apache.commons.compress.parallel.FileBasedScatterGatherBackingStore;
import org.apache.commons.compress.parallel.ScatterGatherBackingStore;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;

/**
 * A ZIP output stream that is optimized for multi-threaded scatter/gather construction of ZIP files.
 * <p>
 * The internal data format of the entries used by this class are entirely private to this class and are not part of any public api whatsoever.
 * </p>
 * <p>
 * It is possible to extend this class to support different kinds of backing storage, the default implementation only supports file-based backing.
 * </p>
 * <p>
 * Thread safety: This class supports multiple threads. But the "writeTo" method must be called by the thread that originally created the
 * {@link ZipArchiveEntry}.
 * </p>
 *
 * @since 1.10
 */
public class ScatterZipOutputStream implements Closeable {

    private static final class CompressedEntry {
        final ZipArchiveEntryRequest zipArchiveEntryRequest;
        final long crc;
        final long compressedSize;
        final long size;

        CompressedEntry(final ZipArchiveEntryRequest zipArchiveEntryRequest, final long crc, final long compressedSize, final long size) {
            this.zipArchiveEntryRequest = zipArchiveEntryRequest;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
        }

        /**
         * Updates the original {@link ZipArchiveEntry} with sizes/CRC. Do not use this method from threads that did not create the instance itself!
         *
         * @return the zipArchiveEntry that is the basis for this request.
         */
        public ZipArchiveEntry transferToArchiveEntry() {
            final ZipArchiveEntry entry = zipArchiveEntryRequest.getZipArchiveEntry();
            entry.setCompressedSize(compressedSize);
            entry.setSize(size);
            entry.setCrc(crc);
            entry.setMethod(zipArchiveEntryRequest.getMethod());
            return entry;
        }
    }

    /**
     * Writes ZIP entries to a ZIP archive.
     */
    public static class ZipEntryWriter implements Closeable {
        private final Iterator<CompressedEntry> itemsIterator;
        private final InputStream inputStream;

        /**
         * Constructs a new instance.
         *
         * @param out a ScatterZipOutputStream.
         * @throws IOException if an I/O error occurs.
         */
        public ZipEntryWriter(final ScatterZipOutputStream out) throws IOException {
            out.backingStore.closeForWriting();
            itemsIterator = out.items.iterator();
            inputStream = out.backingStore.getInputStream();
        }

        @Override
        public void close() throws IOException {
            IOUtils.close(inputStream);
        }

        /**
         * Writes the next ZIP entry to the given target.
         *
         * @param target Where to write.
         * @throws IOException if an I/O error occurs.
         */
        public void writeNextZipEntry(final ZipArchiveOutputStream target) throws IOException {
            final CompressedEntry compressedEntry = itemsIterator.next();
            // @formatter:off
            try (BoundedInputStream rawStream = BoundedInputStream.builder()
                    .setInputStream(inputStream)
                    .setMaxCount(compressedEntry.compressedSize)
                    .setPropagateClose(false)
                    .get()) {
                target.addRawArchiveEntry(compressedEntry.transferToArchiveEntry(), rawStream);
            }
            // @formatter:on
        }
    }

    /**
     * Creates a {@link ScatterZipOutputStream} with default compression level that is backed by a file
     *
     * @param file The file to offload compressed data into.
     * @return A ScatterZipOutputStream that is ready for use.
     * @throws FileNotFoundException if the file cannot be found
     */
    public static ScatterZipOutputStream fileBased(final File file) throws FileNotFoundException {
        return pathBased(file.toPath(), Deflater.DEFAULT_COMPRESSION);
    }

    /**
     * Creates a {@link ScatterZipOutputStream} that is backed by a file
     *
     * @param file             The file to offload compressed data into.
     * @param compressionLevel The compression level to use, @see #Deflater
     * @return A ScatterZipOutputStream that is ready for use.
     * @throws FileNotFoundException if the file cannot be found
     */
    public static ScatterZipOutputStream fileBased(final File file, final int compressionLevel) throws FileNotFoundException {
        return pathBased(file.toPath(), compressionLevel);
    }

    /**
     * Creates a {@link ScatterZipOutputStream} with default compression level that is backed by a file
     *
     * @param path The path to offload compressed data into.
     * @return A ScatterZipOutputStream that is ready for use.
     * @throws FileNotFoundException if the path cannot be found
     * @since 1.22
     */
    public static ScatterZipOutputStream pathBased(final Path path) throws FileNotFoundException {
        return pathBased(path, Deflater.DEFAULT_COMPRESSION);
    }

    /**
     * Creates a {@link ScatterZipOutputStream} that is backed by a file
     *
     * @param path             The path to offload compressed data into.
     * @param compressionLevel The compression level to use, @see #Deflater
     * @return A ScatterZipOutputStream that is ready for use.
     * @throws FileNotFoundException if the path cannot be found
     * @since 1.22
     */
    public static ScatterZipOutputStream pathBased(final Path path, final int compressionLevel) throws FileNotFoundException {
        final ScatterGatherBackingStore bs = new FileBasedScatterGatherBackingStore(path);
        // lifecycle is bound to the ScatterZipOutputStream returned
        final StreamCompressor sc = StreamCompressor.create(compressionLevel, bs); // NOSONAR
        return new ScatterZipOutputStream(bs, sc);
    }

    private final Queue<CompressedEntry> items = new ConcurrentLinkedQueue<>();

    private final ScatterGatherBackingStore backingStore;

    private final StreamCompressor streamCompressor;

    private final AtomicBoolean isClosed = new AtomicBoolean();

    private ZipEntryWriter zipEntryWriter;

    /**
     * Constructs a new instance.
     *
     * @param backingStore the backing store.
     * @param streamCompressor Deflates ZIP entries.
     */
    public ScatterZipOutputStream(final ScatterGatherBackingStore backingStore, final StreamCompressor streamCompressor) {
        this.backingStore = backingStore;
        this.streamCompressor = streamCompressor;
    }

    /**
     * Adds an archive entry to this scatter stream.
     *
     * @param zipArchiveEntryRequest The entry to write.
     * @throws IOException If writing fails
     */
    public void addArchiveEntry(final ZipArchiveEntryRequest zipArchiveEntryRequest) throws IOException {
        try (InputStream payloadStream = zipArchiveEntryRequest.getPayloadStream()) {
            streamCompressor.deflate(payloadStream, zipArchiveEntryRequest.getMethod());
        }
        items.add(new CompressedEntry(zipArchiveEntryRequest, streamCompressor.getCrc32(), streamCompressor.getBytesWrittenForLastEntry(),
                streamCompressor.getBytesRead()));
    }

    /**
     * Closes this stream, freeing all resources involved in the creation of this stream.
     *
     * @throws IOException If closing fails
     */
    @Override
    public void close() throws IOException {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            IOUtils.close(zipEntryWriter);
            backingStore.close();
        } finally {
            streamCompressor.close();
        }
    }

    /**
     * Writes the contents of this scatter stream to a target archive.
     *
     * @param target The archive to receive the contents of this {@link ScatterZipOutputStream}.
     * @throws IOException If writing fails
     * @see #zipEntryWriter()
     */
    public void writeTo(final ZipArchiveOutputStream target) throws IOException {
        backingStore.closeForWriting();
        try (InputStream data = backingStore.getInputStream()) {
            for (final CompressedEntry compressedEntry : items) {
                // @formatter:off
                try (BoundedInputStream rawStream = BoundedInputStream.builder()
                        .setInputStream(data)
                        .setMaxCount(compressedEntry.compressedSize)
                        .setPropagateClose(false)
                        .get()) {
                    target.addRawArchiveEntry(compressedEntry.transferToArchiveEntry(), rawStream);
                }
                // @formatter:on
            }
        }
    }

    /**
     * Gets a ZIP entry writer for this scatter stream.
     *
     * @throws IOException If getting scatter stream input stream
     * @return the ZipEntryWriter created on first call of the method
     */
    public ZipEntryWriter zipEntryWriter() throws IOException {
        if (zipEntryWriter == null) {
            zipEntryWriter = new ZipEntryWriter(this);
        }
        return zipEntryWriter;
    }
}
