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
package org.apache.commons.compress.archivers.dump;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Stack;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipEncoding;
import org.apache.commons.compress.archivers.zip.ZipEncodingHelper;
import org.apache.commons.compress.utils.IOUtils;

/**
 * The DumpArchiveInputStream reads a Unix dump archive as an InputStream. Methods are provided to position at each successive entry in the archive, and the
 * read each entry as a normal input stream using read().
 * <p>
 * There doesn't seem to exist a hint on the encoding of string values in any piece documentation. Given the main purpose of dump/restore is backing up a system
 * it seems very likely the format uses the current default encoding of the system.
 * </p>
 * @NotThreadSafe
 * @since 1.3
 */
public class DumpArchiveInputStream extends ArchiveInputStream<DumpArchiveEntry> {

    private static final String CURRENT_PATH_SEGMENT = ".";
    private static final String PARENT_PATH_SEGMENT = "..";

    /**
     * Look at the first few bytes of the file to decide if it's a dump archive. With 32 bytes we can look at the magic value, with a full 1k we can verify the
     * checksum.
     *
     * @param buffer data to match
     * @param length length of data
     * @return whether the buffer seems to contain dump data
     */
    public static boolean matches(final byte[] buffer, final int length) {
        // do we have enough of the header?
        if (length < 32) {
            return false;
        }

        // this is the best test
        if (length >= DumpArchiveConstants.TP_SIZE) {
            return DumpArchiveUtil.verify(buffer);
        }

        // this will work in a pinch.
        return DumpArchiveConstants.NFS_MAGIC == DumpArchiveUtil.convert32(buffer, 24);
    }

    private final DumpArchiveSummary summary;
    private DumpArchiveEntry active;
    private boolean isClosed;
    private boolean hasHitEOF;
    private long entrySize;
    private long entryOffset;
    private int readIdx;
    private final byte[] readBuf = new byte[DumpArchiveConstants.TP_SIZE];
    private byte[] blockBuffer;
    private int recordOffset;
    private long filepos;

    /**
     * TapeInputStream is the raw input.
     */
    protected TapeInputStream raw;

    /** Map of ino -> dirent entry. We can use this to reconstruct full paths. */
    private final Map<Integer, Dirent> names = new HashMap<>();

    /** Map of ino -> (directory) entry when we're missing one or more elements in the path. */
    private final Map<Integer, DumpArchiveEntry> pending = new HashMap<>();

    /** Queue of (directory) entries where we now have the full path. */
    private final Queue<DumpArchiveEntry> queue;

    /**
     * The encoding to use for file names and labels.
     */
    private final ZipEncoding zipEncoding;

    /**
     * Constructor using the platform's default encoding for file names.
     *
     * @param is stream to read from
     * @throws ArchiveException on error
     */
    public DumpArchiveInputStream(final InputStream is) throws ArchiveException {
        this(is, null);
    }

    /**
     * Constructs a new instance.
     *
     * @param is       stream to read from
     * @param encoding the encoding to use for file names, use null for the platform's default encoding
     * @throws ArchiveException on error
     * @since 1.6
     */
    public DumpArchiveInputStream(final InputStream is, final String encoding) throws ArchiveException {
        super(is, encoding);
        this.raw = new TapeInputStream(is);
        this.hasHitEOF = false;
        this.zipEncoding = ZipEncodingHelper.getZipEncoding(encoding);

        try {
            // read header, verify it's a dump archive.
            final byte[] headerBytes = raw.readRecord();

            if (!DumpArchiveUtil.verify(headerBytes)) {
                throw new UnrecognizedFormatException();
            }

            // get summary information
            summary = new DumpArchiveSummary(headerBytes, this.zipEncoding);

            // reset buffer with actual block size.
            raw.resetBlockSize(summary.getNTRec(), summary.isCompressed());

            // allocate our read buffer.
            blockBuffer = new byte[4 * DumpArchiveConstants.TP_SIZE];

            // skip past CLRI and BITS segments since we don't handle them yet.
            readCLRI();
            readBITS();
        } catch (final IOException e) {
            throw new ArchiveException(e.getMessage(), (Throwable) e);
        }

        // put in a dummy record for the root node.
        final Dirent root = new Dirent(2, 2, 4, CURRENT_PATH_SEGMENT);
        names.put(2, root);

        // use priority based on queue to ensure parent directories are
        // released first.
        queue = new PriorityQueue<>(10, (p, q) -> {
            if (p.getOriginalName() == null || q.getOriginalName() == null) {
                return Integer.MAX_VALUE;
            }

            return p.getOriginalName().compareTo(q.getOriginalName());
        });
    }

    /**
     * Closes the stream for this entry.
     */
    @Override
    public void close() throws IOException {
        if (!isClosed) {
            isClosed = true;
            raw.close();
        }
    }

    @Override
    public long getBytesRead() {
        return raw.getBytesRead();
    }

    @Deprecated
    @Override
    public int getCount() {
        return (int) getBytesRead();
    }

    /**
     * Reads the next entry.
     *
     * @return the next entry
     * @throws IOException on error
     * @deprecated Use {@link #getNextEntry()}.
     */
    @Deprecated
    public DumpArchiveEntry getNextDumpEntry() throws IOException {
        return getNextEntry();
    }

    @Override
    public DumpArchiveEntry getNextEntry() throws IOException {
        DumpArchiveEntry entry = null;
        String path = null;

        // is there anything in the queue?
        if (!queue.isEmpty()) {
            return queue.remove();
        }

        while (entry == null) {
            if (hasHitEOF) {
                return null;
            }

            // skip any remaining records in this segment for prior file.
            // we might still have holes... easiest to do it
            // block by block. We may want to revisit this if
            // the unnecessary decompression time adds up.
            while (readIdx < active.getHeaderCount()) {
                if (!active.isSparseRecord(readIdx++) && raw.skip(DumpArchiveConstants.TP_SIZE) == -1) {
                    throw new EOFException();
                }
            }

            readIdx = 0;
            filepos = raw.getBytesRead();

            byte[] headerBytes = raw.readRecord();

            if (!DumpArchiveUtil.verify(headerBytes)) {
                throw new InvalidFormatException();
            }

            active = DumpArchiveEntry.parse(headerBytes);

            // skip any remaining segments for prior file.
            while (DumpArchiveConstants.SEGMENT_TYPE.ADDR == active.getHeaderType()) {
                if (raw.skip((long) DumpArchiveConstants.TP_SIZE * (active.getHeaderCount() - active.getHeaderHoles())) == -1) {
                    throw new EOFException();
                }

                filepos = raw.getBytesRead();
                headerBytes = raw.readRecord();

                if (!DumpArchiveUtil.verify(headerBytes)) {
                    throw new InvalidFormatException();
                }

                active = DumpArchiveEntry.parse(headerBytes);
            }

            // check if this is an end-of-volume marker.
            if (DumpArchiveConstants.SEGMENT_TYPE.END == active.getHeaderType()) {
                hasHitEOF = true;

                return null;
            }

            entry = active;

            if (entry.isDirectory()) {
                readDirectoryEntry(active);

                // now we create an empty InputStream.
                entryOffset = 0;
                entrySize = 0;
                readIdx = active.getHeaderCount();
            } else {
                entryOffset = 0;
                entrySize = active.getEntrySize();
                readIdx = 0;
            }

            recordOffset = readBuf.length;

            path = getPath(entry);

            if (path == null) {
                entry = null;
            }
        }

        entry.setName(path);
        entry.setSimpleName(names.get(entry.getIno()).getName());
        entry.setOffset(filepos);

        return entry;
    }

    /**
     * Gets full path for specified archive entry, or null if there's a gap.
     *
     * @param entry
     * @return full path for specified archive entry, or null if there's a gap.
     * @throws DumpArchiveException Infinite loop detected in directory entries.
     */
    private String getPath(final DumpArchiveEntry entry) throws DumpArchiveException {
        // build the stack of elements. It's possible that we're
        // still missing an intermediate value and if so we
        final Stack<String> elements = new Stack<>();
        final BitSet visited = new BitSet();
        Dirent dirent = null;
        for (int i = entry.getIno();; i = dirent.getParentIno()) {
            if (!names.containsKey(i)) {
                elements.clear();
                break;
            }
            if (visited.get(i)) {
                throw new DumpArchiveException("Duplicate node " + i);
            }
            dirent = names.get(i);
            visited.set(i);
            elements.push(dirent.getName());
            if (dirent.getIno() == dirent.getParentIno()) {
                break;
            }
        }
        // if an element is missing defer the work and read next entry.
        if (elements.isEmpty()) {
            pending.put(entry.getIno(), entry);
            return null;
        }
        // generate full path from stack of elements.
        final StringBuilder sb = new StringBuilder(elements.pop());
        while (!elements.isEmpty()) {
            sb.append('/');
            sb.append(elements.pop());
        }
        return sb.toString();
    }

    /**
     * Gets the archive summary information.
     *
     * @return the summary
     */
    public DumpArchiveSummary getSummary() {
        return summary;
    }

    /**
     * Reads bytes from the current dump archive entry.
     *
     * This method is aware of the boundaries of the current entry in the archive and will deal with them as if they were this stream's start and EOF.
     *
     * @param buf The buffer into which to place bytes read.
     * @param off The offset at which to place bytes read.
     * @param len The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    @Override
    public int read(final byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int totalRead = 0;

        if (hasHitEOF || isClosed || entryOffset >= entrySize) {
            return -1;
        }

        if (active == null) {
            throw new IllegalStateException("No current dump entry");
        }

        if (len + entryOffset > entrySize) {
            len = (int) (entrySize - entryOffset);
        }

        while (len > 0) {
            final int sz = Math.min(len, readBuf.length - recordOffset);

            // copy any data we have
            if (recordOffset + sz <= readBuf.length) {
                System.arraycopy(readBuf, recordOffset, buf, off, sz);
                totalRead += sz;
                recordOffset += sz;
                len -= sz;
                off += sz;
            }

            // load next block if necessary.
            if (len > 0) {
                if (readIdx >= 512) {
                    final byte[] headerBytes = raw.readRecord();

                    if (!DumpArchiveUtil.verify(headerBytes)) {
                        throw new InvalidFormatException();
                    }

                    active = DumpArchiveEntry.parse(headerBytes);
                    readIdx = 0;
                }

                if (!active.isSparseRecord(readIdx++)) {
                    final int r = raw.read(readBuf, 0, readBuf.length);
                    if (r != readBuf.length) {
                        throw new EOFException();
                    }
                } else {
                    Arrays.fill(readBuf, (byte) 0);
                }

                recordOffset = 0;
            }
        }

        entryOffset += totalRead;

        return totalRead;
    }

    /**
     * Reads BITS segment.
     */
    private void readBITS() throws IOException {
        final byte[] buffer = raw.readRecord();

        if (!DumpArchiveUtil.verify(buffer)) {
            throw new InvalidFormatException();
        }

        active = DumpArchiveEntry.parse(buffer);

        if (DumpArchiveConstants.SEGMENT_TYPE.BITS != active.getHeaderType()) {
            throw new InvalidFormatException();
        }

        // we don't do anything with this yet.
        if (raw.skip((long) DumpArchiveConstants.TP_SIZE * active.getHeaderCount()) == -1) {
            throw new EOFException();
        }
        readIdx = active.getHeaderCount();
    }

    /**
     * Reads CLRI (deleted inode) segment.
     */
    private void readCLRI() throws IOException {
        final byte[] buffer = raw.readRecord();

        if (!DumpArchiveUtil.verify(buffer)) {
            throw new InvalidFormatException();
        }

        active = DumpArchiveEntry.parse(buffer);

        if (DumpArchiveConstants.SEGMENT_TYPE.CLRI != active.getHeaderType()) {
            throw new InvalidFormatException();
        }

        // we don't do anything with this yet.
        if (raw.skip((long) DumpArchiveConstants.TP_SIZE * active.getHeaderCount()) == -1) {
            throw new EOFException();
        }
        readIdx = active.getHeaderCount();
    }

    /**
     * Reads directory entry.
     */
    private void readDirectoryEntry(DumpArchiveEntry entry) throws IOException {
        long size = entry.getEntrySize();
        boolean first = true;

        while (first || DumpArchiveConstants.SEGMENT_TYPE.ADDR == entry.getHeaderType()) {
            // read the header that we just peeked at.
            if (!first) {
                raw.readRecord();
            }

            if (!names.containsKey(entry.getIno()) && DumpArchiveConstants.SEGMENT_TYPE.INODE == entry.getHeaderType()) {
                pending.put(entry.getIno(), entry);
            }

            final int datalen = DumpArchiveConstants.TP_SIZE * entry.getHeaderCount();

            if (blockBuffer.length < datalen) {
                blockBuffer = IOUtils.readRange(raw, datalen);
                if (blockBuffer.length != datalen) {
                    throw new EOFException();
                }
            } else if (raw.read(blockBuffer, 0, datalen) != datalen) {
                throw new EOFException();
            }

            int reclen = 0;

            for (int i = 0; i < datalen - 8 && i < size - 8; i += reclen) {
                final int ino = DumpArchiveUtil.convert32(blockBuffer, i);
                reclen = DumpArchiveUtil.convert16(blockBuffer, i + 4);
                if (reclen == 0) {
                    throw new DumpArchiveException("reclen cannot be 0");
                }

                final byte type = blockBuffer[i + 6];

                final String name = DumpArchiveUtil.decode(zipEncoding, blockBuffer, i + 8, blockBuffer[i + 7]);

                if (CURRENT_PATH_SEGMENT.equals(name) || PARENT_PATH_SEGMENT.equals(name)) {
                    // do nothing...
                    continue;
                }

                final Dirent d = new Dirent(ino, entry.getIno(), type, name);

                /*
                 * if ((type == 4) && names.containsKey(ino)) { System.out.println("we already have ino: " + names.get(ino)); }
                 */

                names.put(ino, d);

                // check whether this allows us to fill anything in the pending list.
                for (final Map.Entry<Integer, DumpArchiveEntry> mapEntry : pending.entrySet()) {
                    final DumpArchiveEntry v = mapEntry.getValue();
                    final String path = getPath(v);
                    if (path != null) {
                        v.setName(path);
                        v.setSimpleName(names.get(mapEntry.getKey()).getName());
                        queue.add(v);
                    }
                }

                // remove anything that we found. (We can't do it earlier
                // because of concurrent modification exceptions.)
                queue.forEach(e -> pending.remove(e.getIno()));
            }

            final byte[] peekBytes = raw.peek();

            if (!DumpArchiveUtil.verify(peekBytes)) {
                throw new InvalidFormatException();
            }

            entry = DumpArchiveEntry.parse(peekBytes);
            first = false;
            size -= DumpArchiveConstants.TP_SIZE;
        }
    }

}
